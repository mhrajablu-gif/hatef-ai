# Yadet 2 — Baseline Code Audit

Audit date: 2026-11-27
Audited ref: main @ 9b5486b8490f8931cbffb447a7f1079327c2373c
Purpose: identify reusable foundations and risks before implementation. This is a source review, not a claim that runtime behavior has been verified.

## Reusable foundations observed

- Kotlin Android application with Jetpack Compose UI.
- Room persistence and a reminder entity/repository already exist.
- PersianDateEngine provides Jalali/Gregorian conversion helpers and Persian digit conversion.
- AlarmManager-based scheduling, notification receiver, and boot receiver exist.
- Existing unit tests cover selected date conversion, relative time phrases, weekday parsing, and a multi-task parser path.
- Manifest already declares notification, exact-alarm, boot, full-screen intent, and network permissions.

## Risks and gaps observed in source

1. The reminder entity stores recurrence primarily as string fields (repeatType, recurrenceDaysOfWeek, recurrenceEndDate). This is not a sufficiently explicit, strongly validated recurrence model for a dependable recurrence engine.
2. AlarmScheduler schedules the primary trigger timestamp and optional advance alert. In the reviewed implementation, the scheduler itself does not calculate the next recurring occurrence.
3. BootReceiver reschedules active records from their stored trigger timestamp. This restores only what is represented as active database rows; recurrence progression must be implemented and tested explicitly.
4. GeminiSemanticParser contains Firebase model setup, but its public parseCommand path directly delegates to LocalPersianParser. The online model method is separate and is not used by that public path in the reviewed source.
5. The existing test suite has useful examples, but it does not yet demonstrate full coverage of recurrence progression, all adjacent-task parsing forms, alarm recovery, or offline-vs-online provider status.
6. Existing UI and service layers are coupled to the legacy package/application architecture. The new test app must use a distinct application ID and preserve the old app and its data.

## Proposed Yadet 2 module boundaries

- domain/model: Reminder, RecurrenceRule, ReminderIntent, ParsedReminder, ParseOutcome.
- domain/usecase: CreateReminder, UpdateReminder, DeleteReminder, CompleteReminder, SnoozeReminder, GetReminders, ParseUserMessage.
- domain/recurrence: pure recurrence calculator with no Android dependencies.
- domain/parser: deterministic Persian local parser and intent classifier.
- data/local: Room entities, DAO, migrations, repository implementation.
- platform/alarm: AlarmManager adapter, notification receiver, boot/time/timezone reconciliation.
- ai/provider: optional provider interface, Gemini adapter, schema validation, explicit connection state.
- ui: Compose screens and view models; UI must not own date parsing or recurrence calculations.

## Decisions required before implementation

- Define canonical recurrence rules and behavior for dates that do not exist in a target month/year (e.g. 31st day, Esfand 30).
- Define whether yearly recurrence without a date is clarification-needed (it should be).
- Define reminder time-zone behavior when traveling; store a clear zone policy.
- Confirm the online AI provider and secure credential delivery before enabling it. Do not assume Firebase project configuration or API keys are available.
- Confirm new application ID for side-by-side installation; use the previously agreed safe test package only if it is still available and not already occupied.

## Verification boundary

This audit is based on reading source files and tests on main. No Android emulator/device execution, alarm delivery, network request, or user-data migration was performed as part of this audit.
