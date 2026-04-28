package com.sidms.backend.service;

import com.sidms.backend.entity.*;
import com.sidms.backend.repository.*;
import com.sidms.backend.service.notification.NotificationChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * EventDrivenNotificationService - Core of the alert/notification system.
 * 
 * Architecture:
 * 1. AlertRuleEvaluator creates WeatherEvents (real-world occurrences)
 * 2. This service processes unprocessed WeatherEvents
 * 3. For each event, creates user Notifications (personalized)
 * 4. For each notification, creates NotificationDeliveries (per-channel tracking)
 * 5. Channel handlers (Email, SMS, InApp) process deliveries
 * 
 * Key features:
 * - Deduplication via EventTrigger (hash-based cooldown)
 * - Multi-channel delivery with independent retry
 * - User preference respect
 * - Background processing via @Scheduled
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EventDrivenNotificationService {

    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_DATE_TIME;

    private final WeatherEventRepository weatherEventRepository;
    private final SosEventRepository sosEventRepository;
    private final EventTriggerRepository eventTriggerRepository;
    private final NotificationRepository notificationRepository;
    private final NotificationDeliveryRepository deliveryRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final UserRepository userRepository;
    private final UserPreferencesRepository userPreferencesRepository;
    private final SyncStateService syncStateService;
    private final List<NotificationChannel> channels;
    private final PlatformTransactionManager transactionManager;
    private final EntityManager entityManager;
    private TransactionTemplate newTransactionTemplate;

    private static final String JOB_PROCESS_EVENTS = "notification_event_processing";
    private static final String JOB_PROCESS_SOS_EVENTS = "sos_event_processing";
    private static final String JOB_RETRY_DELIVERIES = "notification_delivery_retry";
    private static final java.time.Duration COOLDOWN = java.time.Duration.ofMinutes(1);
    private static final java.time.Duration SOS_COOLDOWN = java.time.Duration.ofSeconds(10);

    /**
     * Result of trigger creation attempt.
     * @param trigger the trigger (either newly created or existing)
     * @param created true if this thread successfully created the trigger, false if another thread created it
     */
    private record TriggerResult(EventTrigger trigger, boolean created) {}

    // -------------------------------------------------------------------------
    // Event Processing (WeatherEvent → Notifications)
    // -------------------------------------------------------------------------

    /**
     * Process unprocessed WeatherEvents and create user notifications.
     * Runs every 2 minutes.
     */
    @PostConstruct
    public void init() {
        // Create a TransactionTemplate that always starts a new transaction
        this.newTransactionTemplate = new TransactionTemplate(transactionManager);
        this.newTransactionTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
    }

    @Scheduled(fixedDelay = 2 * 60 * 1000, initialDelay = 30 * 1000)
    @Transactional
    public void scheduledProcessEvents() {
        if (!syncStateService.shouldRun(JOB_PROCESS_EVENTS, COOLDOWN)) {
            return;
        }

        try {
            processUnprocessedEvents();
            syncStateService.recordSuccess(JOB_PROCESS_EVENTS, COOLDOWN);
        } catch (Exception e) {
            log.error("[EventDrivenNotification] Event processing failed: {}", e.getMessage(), e);
            syncStateService.recordFailure(JOB_PROCESS_EVENTS, COOLDOWN, e.getMessage());
        }
    }

    /**
     * Public entry point for manual/admin triggers.
     */
    @Transactional
    public void processUnprocessedEvents() {
        List<WeatherEvent> unprocessed = weatherEventRepository.findByIsProcessedFalseOrderByCreatedAtAsc();

        if (unprocessed.isEmpty()) {
            return;
        }

        log.info("[EventDrivenNotification] Processing {} unprocessed events", unprocessed.size());

        int processed = 0;
        int skipped = 0;

        for (WeatherEvent event : unprocessed) {
            try {
                boolean shouldNotify = processEvent(event);
                if (shouldNotify) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to process event {}: {}",
                        event.getId(), e.getMessage());
            }
        }

        log.info("[EventDrivenNotification] Completed: {} processed, {} skipped", processed, skipped);
    }

    // -------------------------------------------------------------------------
    // SOS Event Processing (SosEvent → Notifications to Responders)
    // -------------------------------------------------------------------------

    /**
     * Process unprocessed SosEvents and create responder notifications.
     * Runs every 30 seconds for faster SOS response.
     */
    @Scheduled(fixedDelay = 30 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void scheduledProcessSosEvents() {
        if (!syncStateService.shouldRun(JOB_PROCESS_SOS_EVENTS, SOS_COOLDOWN)) {
            return;
        }

        try {
            processUnprocessedSosEvents();
            syncStateService.recordSuccess(JOB_PROCESS_SOS_EVENTS, SOS_COOLDOWN);
        } catch (Exception e) {
            log.error("[EventDrivenNotification] SOS event processing failed: {}", e.getMessage(), e);
            syncStateService.recordFailure(JOB_PROCESS_SOS_EVENTS, SOS_COOLDOWN, e.getMessage());
        }
    }

    /**
     * Public entry point for manual/admin SOS processing.
     */
    @Transactional
    public void processUnprocessedSosEvents() {
        List<SosEvent> unprocessed = sosEventRepository.findByIsProcessedFalseOrderByCreatedAtAsc();

        if (unprocessed.isEmpty()) {
            return;
        }

        log.info("[EventDrivenNotification] Processing {} unprocessed SOS events", unprocessed.size());

        int processed = 0;
        int skipped = 0;

        for (SosEvent event : unprocessed) {
            try {
                boolean shouldNotify = processSosEvent(event);
                if (shouldNotify) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to process SOS event {}: {}",
                        event.getId(), e.getMessage());
            }
        }

        log.info("[EventDrivenNotification] SOS processing completed: {} processed, {} skipped", processed, skipped);
    }

    /**
     * Process a single SosEvent.
     *
     * @return true if notifications were created, false if deduplicated/skipped
     */
    private boolean processSosEvent(SosEvent event) {
        // 1. Generate hash with null-safe spatial unit handling
        String eventHash = generateSosEventHash(event);

        // 2. Check deduplication via EventTrigger
        boolean isDuplicate = eventTriggerRepository.isTriggerActive(eventHash, LocalDateTime.now(ZoneOffset.UTC));

        if (isDuplicate) {
            log.debug("[EventDrivenNotification] SOS event {} is duplicate (hash: {}), skipping",
                    event.getId(), eventHash);
            markSosEventProcessed(event, null);
            return false;
        }

        // 3. Get target responders (admins, responders, volunteers)
        List<User> targetResponders = determineSosTargetResponders(event);

        if (targetResponders.isEmpty()) {
            log.warn("[EventDrivenNotification] No target responders for SOS event {}. Ensure users have RESPONDER/VOLUNTEER roles.",
                    event.getId());
            markSosEventProcessed(event, null);
            return false;
        }

        // 4. Create EventTrigger atomically - returns result indicating if this thread won the race
        TriggerResult result = createSosEventTrigger(event, eventHash);

        // 5. Only create notifications if this thread successfully created the trigger
        // If another thread won the race, skip notification creation for this event
        if (!result.created()) {
            log.debug("[EventDrivenNotification] Another thread processed hash {} for SOS event {}, skipping notifications",
                    eventHash, event.getId());
            markSosEventProcessed(event, result.trigger().getId());
            return false;
        }

        // 6. Create notifications for each responder
        int notificationCount = 0;
        for (User responder : targetResponders) {
            try {
                createSosNotificationForResponder(event, responder);
                notificationCount++;
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to create SOS notification for responder {}: {}",
                        responder.getId(), e.getMessage());
            }
        }

        // 7. Mark event as processed
        markSosEventProcessed(event, result.trigger().getId());

        log.info("[EventDrivenNotification] Created {} responder notifications for SOS event {} (incident: {})",
                notificationCount, event.getId(), event.getIncidentId());

        return notificationCount > 0;
    }

    private void markSosEventProcessed(SosEvent event, UUID triggerId) {
        event.setIsProcessed(true);
        event.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        sosEventRepository.save(event);
    }

    private TriggerResult createSosEventTrigger(SosEvent event, String hash) {
        // SOS events have shorter cooldowns since they're emergencies
        int cooldownHours = switch (event.getStatus()) {
            case PENDING -> 1;  // Retry notifications every hour until assigned
            case ASSIGNED -> 6;
            case EN_ROUTE -> 12;
            case RESOLVED -> 24;
        };

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(cooldownHours);

        // Use atomic upsert to handle race conditions and expired triggers. Returns Optional if inserted/updated, empty if active trigger exists.
        Optional<UUID> newTriggerId = eventTriggerRepository.insertOrUpdateIfExpired(
                hash,
                null,  // SOS events don't come from alert rules
                event.getId(),
                event.getSpatialUnitId(),
                "SOS_" + event.getStatus().name(),
                now,
                expiresAt,
                null,
                null,
                now
        );

        boolean created = newTriggerId.isPresent();

        // Fetch the trigger (either newly created or existing)
        EventTrigger trigger = eventTriggerRepository.findByEventHash(hash)
                .orElseThrow(() -> new IllegalStateException("Trigger with hash " + hash + " not found after insert attempt"));

        if (created) {
            log.debug("[EventDrivenNotification] Created new SOS trigger {} for hash {}", trigger.getId(), hash);
        } else {
            log.debug("[EventDrivenNotification] Found existing trigger {} for hash {} (race condition)", trigger.getId(), hash);
        }

        return new TriggerResult(trigger, created);
    }

    private List<User> determineSosTargetResponders(SosEvent event) {
        // Find all users with responder, admin, or volunteer roles
        List<User> responders = userRepository.findAll().stream()
                .filter(User::getIsActive)
                .filter(u -> u.getRoles() != null && u.getRoles().stream()
                        .anyMatch(r -> {
                            String roleName = r.getName().toUpperCase();
                            return roleName.contains("RESPONDER") ||
                                   roleName.contains("ADMIN") ||
                                   roleName.contains("VOLUNTEER") ||
                                   roleName.contains("GOVT");
                        }))
                .toList();

        // TODO: Future enhancement - filter by proximity to incident location
        // TODO: Future enhancement - filter by availability status

        return responders;
    }

    private void createSosNotificationForResponder(SosEvent event, User responder) {
        // Build notification title and body
        String title = String.format("🚨 SOS ALERT: %s in %s",
                event.getStatus().name(),
                event.getSpatialUnitName() != null ? event.getSpatialUnitName() : "Unknown Area");

        StringBuilder body = new StringBuilder();
        body.append(String.format("Emergency SOS triggered by %s", event.getUserName()));
        if (event.getUserPhone() != null) {
            body.append(String.format(" (Phone: %s)", event.getUserPhone()));
        }
        body.append(".\n\n");

        if (event.getMedicalNotes() != null && !event.getMedicalNotes().isBlank()) {
            body.append(String.format("Medical Notes: %s\n\n", event.getMedicalNotes()));
        }

        body.append(String.format("Location: %s, %s\n",
                event.getLatitude(), event.getLongitude()));

        if (event.getWeatherContext() != null) {
            body.append(String.format("Weather Context: %s\n", event.getWeatherContext()));
        }

        body.append(String.format("Battery Level: %.0f%%\n",
                event.getBatteryLevel() != null ? event.getBatteryLevel() : 100));

        // Create notification
        Notification notification = Notification.builder()
                .userId(responder.getId())
                .type("SOS_EMERGENCY")
                .title(title)
                .body(body.toString())
                .priority(event.getSeverity().ordinal())
                .sourceEntityType("SosIncident")
                .sourceEntityId(event.getIncidentId().toString())
                .actionUrl("/operations?sos=" + event.getIncidentId())
                .isRead(false)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .expiresAt(LocalDateTime.now(ZoneOffset.UTC).plusHours(24))
                .build();

        notification = notificationRepository.save(notification);

        // Create deliveries for each channel the responder has enabled
        var prefs = userPreferencesRepository.findById(responder.getId());
        boolean emailEnabled = prefs.map(p -> Boolean.TRUE.equals(p.getNotifEmail())).orElse(true);
        boolean smsEnabled = prefs.map(p -> Boolean.TRUE.equals(p.getNotifSms())).orElse(false);
        boolean inAppEnabled = true;  // In-app is always enabled

        if (emailEnabled) {
            createDelivery(notification, responder, NotificationDelivery.Channel.EMAIL);
        }
        if (smsEnabled) {
            createDelivery(notification, responder, NotificationDelivery.Channel.SMS);
        }
        if (inAppEnabled) {
            createDelivery(notification, responder, NotificationDelivery.Channel.IN_APP);
        }

        log.debug("[EventDrivenNotification] Created SOS notification {} for responder {} (channels: EMAIL={}, SMS={}, IN_APP={})",
                notification.getId(), responder.getId(), emailEnabled, smsEnabled, inAppEnabled);
    }

    private void createDelivery(Notification notification, User user, NotificationDelivery.Channel channel) {
        NotificationDelivery delivery = NotificationDelivery.builder()
                .notificationId(notification.getId())
                .userId(user.getId())
                .channel(channel)
                .status(NotificationDelivery.Status.PENDING)
                .attemptCount(0)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        deliveryRepository.save(delivery);

        // For IN_APP, mark as delivered immediately
        if (channel == NotificationDelivery.Channel.IN_APP) {
            delivery.setStatus(NotificationDelivery.Status.DELIVERED);
            delivery.setDeliveredAt(LocalDateTime.now(ZoneOffset.UTC));
            delivery.setAttemptCount(1);
            deliveryRepository.save(delivery);
        }
    }

    /**
     * Process a single WeatherEvent.
     *
     * @return true if notifications were created, false if deduplicated/skipped
     */
    private boolean processEvent(WeatherEvent event) {
        // Fetch rule if applicable
        AlertRule rule = null;
        if (event.getSourceRuleId() != null) {
            rule = alertRuleRepository.findById(event.getSourceRuleId()).orElse(null);
        }

        // 1. Check deduplication via EventTrigger
        String eventHash = generateEventHash(event);
        boolean isDuplicate = eventTriggerRepository.isTriggerActive(eventHash, LocalDateTime.now(ZoneOffset.UTC));

        if (isDuplicate) {
            log.debug("[EventDrivenNotification] Event {} is duplicate (hash: {}), skipping",
                    event.getId(), eventHash);
            markEventProcessed(event, null);
            return false;
        }

        // 2. Get target users
        List<User> targetUsers = determineTargetUsers(event, rule);

        if (targetUsers.isEmpty()) {
            log.debug("[EventDrivenNotification] No target users for event {}", event.getId());
            markEventProcessed(event, null);
            return false;
        }

        // 3. Create EventTrigger atomically - returns result indicating if this thread won the race
        TriggerResult result = createEventTrigger(event, eventHash);

        // 4. Only create notifications if this thread successfully created the trigger
        // If another thread won the race, skip notification creation for this event
        if (!result.created()) {
            log.debug("[EventDrivenNotification] Another thread processed hash {} for event {}, skipping notifications",
                    eventHash, event.getId());
            markEventProcessed(event, result.trigger().getId());
            return false;
        }

        // 5. Create notifications for each target user
        int notificationCount = 0;
        for (User user : targetUsers) {
            try {
                createNotificationForUser(event, user, rule);
                notificationCount++;
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to create notification for user {}: {}",
                        user.getId(), e.getMessage());
            }
        }

        // 6. Mark event as processed
        markEventProcessed(event, result.trigger().getId());

        log.info("[EventDrivenNotification] Created {} notifications for event {} (type: {})",
                notificationCount, event.getId(), event.getEventType());

        return notificationCount > 0;
    }

    private void markEventProcessed(WeatherEvent event, UUID triggerId) {
        event.setIsProcessed(true);
        event.setProcessedAt(LocalDateTime.now(ZoneOffset.UTC));
        weatherEventRepository.save(event);
    }

    private String generateEventHash(WeatherEvent event) {
        String hashInput = String.join("|",
                event.getSourceRuleId() != null ? event.getSourceRuleId().toString() : "SYSTEM",
                event.getSpatialUnitId().toString(),
                event.getEventType(),
                event.getStartTime().toLocalDate().toString());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(hashInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(hashInput.hashCode());
        }
    }

    private String generateSosEventHash(SosEvent event) {
        // Use spatialUnitId if available, otherwise fall back to lat,lng coordinates
        String locationIdentifier;
        if (event.getSpatialUnitId() != null) {
            locationIdentifier = event.getSpatialUnitId().toString();
        } else if (event.getLatitude() != null && event.getLongitude() != null) {
            // Use coordinate hash as fallback when spatialUnitId is not resolved
            locationIdentifier = String.format("%.6f,%.6f", event.getLatitude(), event.getLongitude());
        } else {
            // Last resort fallback - should rarely happen
            locationIdentifier = event.getId().toString();
        }

        String hashInput = String.join("|",
                locationIdentifier,
                "SOS_" + event.getStatus().name(),
                event.getCreatedAt().toLocalDate().toString());

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(hashInput.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                hexString.append(String.format("%02x", b));
            }
            return hexString.toString();
        } catch (Exception e) {
            return Integer.toHexString(hashInput.hashCode());
        }
    }

    private TriggerResult createEventTrigger(WeatherEvent event, String hash) {
        int cooldownHours = switch (event.getSeverity()) {
            case EXTREME, CRITICAL -> 3;
            case HIGH -> 6;
            case MODERATE -> 12;
            case LOW -> 24;
        };

        if (event.getSourceRuleId() != null) {
            Optional<AlertRule> rule = alertRuleRepository.findById(event.getSourceRuleId());
            if (rule.isPresent() && rule.get().getCooldownHours() != null) {
                cooldownHours = rule.get().getCooldownHours();
            }
        }

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime expiresAt = now.plusHours(cooldownHours);

        // Use atomic upsert to handle race conditions and expired triggers. Returns Optional if inserted/updated, empty if active trigger exists.
        Optional<UUID> newTriggerId = eventTriggerRepository.insertOrUpdateIfExpired(
                hash,
                event.getSourceRuleId(),
                event.getId(),
                event.getSpatialUnitId(),
                event.getEventType(),
                now,
                expiresAt,
                event.getTriggerValue(),
                event.getTriggerThreshold(),
                now
        );

        boolean created = newTriggerId.isPresent();

        // Fetch the trigger (either newly created or existing)
        EventTrigger trigger = eventTriggerRepository.findByEventHash(hash)
                .orElseThrow(() -> new IllegalStateException("Trigger with hash " + hash + " not found after insert attempt"));

        if (created) {
            log.debug("[EventDrivenNotification] Created new trigger {} for hash {}", trigger.getId(), hash);
        } else {
            log.debug("[EventDrivenNotification] Found existing trigger {} for hash {} (race condition)", trigger.getId(), hash);
        }

        return new TriggerResult(trigger, created);
    }

    private List<User> determineTargetUsers(WeatherEvent event, AlertRule rule) {
        // Strategy 1: If event has a source rule, notify only that rule's owner
        if (rule != null) {
            Optional<User> user = userRepository.findById(rule.getUserId());
            if (user.isPresent()) {
                return List.of(user.get());
            }
        }

        // Strategy 2: If event has a warning, notify users who saved that location
        // TODO: Implement saved location → user mapping

        // Strategy 3: For system-wide events, notify relevant users
        // TODO: Implement based on user roles and location subscriptions

        return List.of();
    }

    private void createNotificationForUser(WeatherEvent event, User user, AlertRule rule) {
        // 1. Create the notification
        Notification notification = Notification.builder()
                .userId(user.getId())
                .type("WEATHER_" + event.getEventType())
                .title(event.getTitle())
                .body(event.getDescription())
                .spatialUnitId(event.getSpatialUnitId())
                .warningId(event.getWarningId())
                .isRead(false)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .build();

        notification = notificationRepository.save(notification);

        // 2. Create delivery entries for each enabled channel
        for (NotificationChannel channel : channels) {
            try {
                // If it's a rule-based event, ensure the channel was requested in the rule
                boolean requestedByRule = rule == null || 
                        rule.getChannels() == null || 
                        rule.getChannels().isEmpty() || 
                        rule.getChannels().stream().anyMatch(c -> c.equalsIgnoreCase(channel.getChannelType().name()));

                boolean enabled = channel.isEnabledForUser(user);
                
                // Rule-based alerts bypass global opt-in if explicitly requested, but still need contact info
                if (rule != null && requestedByRule) {
                    if (channel.getChannelType() == NotificationDelivery.Channel.SMS) {
                        enabled = user.getPhone() != null && !user.getPhone().isBlank();
                    } else if (channel.getChannelType() == NotificationDelivery.Channel.EMAIL) {
                        enabled = user.getEmail() != null && !user.getEmail().isBlank();
                    } else if (channel.getChannelType() == NotificationDelivery.Channel.IN_APP) {
                        enabled = true;
                    }
                }

                if (requestedByRule && enabled) {
                    NotificationDelivery delivery = NotificationDelivery.builder()
                            .notificationId(notification.getId())
                            .userId(user.getId())
                            .channel(channel.getChannelType())
                            .status(NotificationDelivery.Status.PENDING)
                            .attemptCount(0)
                            .createdAt(LocalDateTime.now(ZoneOffset.UTC))
                            .build();

                    deliveryRepository.save(delivery);
                }
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to create delivery for channel {}: {}",
                        channel.getChannelType(), e.getMessage());
            }
        }

        // 3. Immediately process in-app deliveries (synchronous)
        processInAppDeliveries(notification, user);
    }

    private void processInAppDeliveries(Notification notification, User user) {
        List<NotificationDelivery> pendingDeliveries = deliveryRepository
                .findByNotificationId(notification.getId());

        for (NotificationDelivery delivery : pendingDeliveries) {
            if (delivery.getChannel() == NotificationDelivery.Channel.IN_APP) {
                for (NotificationChannel channel : channels) {
                    if (channel.getChannelType() == NotificationDelivery.Channel.IN_APP) {
                        channel.send(notification, user, delivery);
                        break;
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Delivery Processing (Retries + Async Channels)
    // -------------------------------------------------------------------------

    /**
     * Process pending deliveries (email, SMS) and handle retries.
     * Runs every 5 minutes.
     */
    @Scheduled(fixedDelay = 5 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void scheduledProcessDeliveries() {
        if (!syncStateService.shouldRun(JOB_RETRY_DELIVERIES, COOLDOWN)) {
            return;
        }

        try {
            processPendingDeliveries();
            syncStateService.recordSuccess(JOB_RETRY_DELIVERIES, COOLDOWN);
        } catch (Exception e) {
            log.error("[EventDrivenNotification] Delivery processing failed: {}", e.getMessage(), e);
            syncStateService.recordFailure(JOB_RETRY_DELIVERIES, COOLDOWN, e.getMessage());
        }
    }

    @Transactional
    public void processPendingDeliveries() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        // 1. Process new pending deliveries
        List<NotificationDelivery> pending = deliveryRepository.findPendingDeliveries(now);

        // 2. Process retryable failed deliveries
        List<NotificationDelivery> retryable = deliveryRepository.findRetryableDeliveries(3, now);

        pending.addAll(retryable);

        if (pending.isEmpty()) {
            return;
        }

        log.debug("[EventDrivenNotification] Processing {} deliveries", pending.size());

        for (NotificationDelivery delivery : pending) {
            try {
                processDelivery(delivery);
            } catch (Exception e) {
                log.error("[EventDrivenNotification] Failed to process delivery {}: {}",
                        delivery.getId(), e.getMessage());
            }
        }
    }

    private void processDelivery(NotificationDelivery delivery) {
        // Load notification and user
        Optional<Notification> notificationOpt = notificationRepository.findById(delivery.getNotificationId());
        Optional<User> userOpt = userRepository.findById(delivery.getUserId());

        if (notificationOpt.isEmpty() || userOpt.isEmpty()) {
            log.warn("[EventDrivenNotification] Missing notification or user for delivery {}",
                    delivery.getId());
            return;
        }

        Notification notification = notificationOpt.get();
        User user = userOpt.get();

        // Find the right channel handler
        for (NotificationChannel channel : channels) {
            if (channel.getChannelType() == delivery.getChannel()) {
                boolean success = channel.send(notification, user, delivery);

                if (!success && delivery.getAttemptCount() < channel.getMaxRetries()) {
                    // Schedule retry
                    int delayMinutes = channel.getRetryDelayMinutes(delivery.getAttemptCount());
                    delivery.setNextRetryAt(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(delayMinutes));
                    delivery.setStatus(NotificationDelivery.Status.RETRYING);
                    deliveryRepository.save(delivery);
                }
                break;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API for Manual/Admin Operations
    // -------------------------------------------------------------------------

    /**
     * Manually trigger event processing (admin use).
     */
    public void forceProcessEvents() {
        syncStateService.resetCooldown(JOB_PROCESS_EVENTS);
        scheduledProcessEvents();
    }

    /**
     * Get statistics for the notification system.
     */
    public NotificationStats getStats() {
        long unprocessedEvents = weatherEventRepository.countByIsProcessedFalse();
        long pendingDeliveries = deliveryRepository.countByStatus(NotificationDelivery.Status.PENDING);
        long failedDeliveries = deliveryRepository.countByStatus(NotificationDelivery.Status.FAILED);
        long activeTriggers = eventTriggerRepository.countActive(LocalDateTime.now(ZoneOffset.UTC));

        return new NotificationStats(unprocessedEvents, pendingDeliveries, failedDeliveries, activeTriggers);
    }

    public record NotificationStats(
            long unprocessedEvents,
            long pendingDeliveries,
            long failedDeliveries,
            long activeTriggers) {
    }
}
