# Yadet 2 — Product & Technical Foundation

Status: Draft baseline for implementation
Branch: rebuild/yadet-v2-foundation
Base: main (preserved; no changes to main)

## Product promise

Yadet is an offline-first Persian reminder assistant. Core reminder storage, scheduling, recurrence, notifications, and manual management must work without an account or internet connection. Optional online AI may improve natural-language understanding and general conversation, but must never be required for core reminder behavior.

## Non-goals for the first stable release

- No mandatory sign-in, Firebase account, or cloud sync.
- No dependence on an online model to schedule or fire an alarm.
- No speculative claims that an AI request succeeded when it failed.
- No automatic destructive migration or replacement of the currently installed app.

## Core user journeys

1. Create one reminder from a natural Persian sentence, with or without the phrase «یادم بنداز».
2. Create multiple distinct reminders from one message, including adjacent time/task phrases without an explicit «و».
3. Ask a clarification question when a required date/time/title is missing or ambiguous; do not silently invent critical details.
4. Create true recurring reminders: daily, selected weekdays, weekly, monthly, yearly, workdays, and custom patterns where supported.
5. Edit, delete, complete, snooze, and reschedule reminders.
6. Ask ordinary questions without accidentally creating reminders.
7. Continue viewing and managing saved reminders and receiving alarms while offline.

## Functional requirements

### Reminder data and persistence
- Persist reminders locally in Room.
- Store canonical timestamps and recurrence rules separately from localized display strings.
- Use an explicit recurrence representation (frequency, interval, weekdays/month-day, optional end date) rather than relying only on free-form strings.
- Preserve existing user data in the old application; the new application must use a distinct application ID during testing.
- Add database migrations and migration tests for every schema change.

### Persian date and time
- Support Jalali dates, Persian and Latin digits, colloquial date/time phrases, and timezone-safe timestamp conversion.
- Test month boundaries, leap years, year boundaries, daylight/timezone changes, and past-time handling.
- Never derive a recurrence solely from the first occurrence timestamp.

### Scheduling and notifications
- Schedule the next occurrence of each active reminder with Android AlarmManager.
- On delivery of a recurring reminder, calculate and schedule its next occurrence.
- Reconcile/reschedule active reminders after device reboot, app update, timezone change, and relevant clock changes.
- Handle exact-alarm permission denial visibly and provide a graceful fallback.
- Test notification/full-screen behavior, screen-off delivery, snooze, dismiss, completion, and duplicate delivery.

### Natural-language intent and extraction
- Separate intent classification from task extraction and action execution.
- Classify at minimum: create, list/query, edit, delete, complete, snooze/reschedule, ordinary conversation, and clarification-needed.
- Extract multiple tasks from a single utterance; time expressions must be associated with the correct task.
- Do not require trigger phrases such as «یادم بنداز».
- A clock time alone must not imply reminder intent.
- Validate extracted data before persistence; ambiguous or incomplete commands must ask a concise follow-up.
- Keep a deterministic local parser as the offline baseline. An optional online model can return structured JSON, but outputs must be schema-validated and must not bypass safety/validation rules.
- Add a transparent connection state: online model available, offline fallback, request failed, or quota/authentication issue. Never label a local response as Gemini output.

### AI and networking
- Core app features remain free and offline-capable.
- Online AI is optional and configured explicitly; no API key or Firebase configuration is assumed to exist.
- Do not embed private API keys in the APK or commit secrets.
- Before enabling a provider, document the provider, authentication method, quotas/costs, regional availability constraints, and secure key handling.
- Network failure must fall back to local functionality with an honest status message.

### UX and accessibility
- Persian-first RTL interface with readable Persian typography.
- Ocean Blue palette: #101D35, #1D3557, #36A9C9, #8EDCE6, #F4F7FB.
- Light/dark themes.
- Conversation, upcoming reminders, calendar, and reminder management views.
- Footer credit: «طراحی و ساخته شده توسط m.h.rajablu».
- Keep manual create/edit controls available as a reliable fallback.

## Quality gates (must pass before an APK is offered)

1. Unit tests for Jalali date parsing/conversion and recurrence calculation.
2. Unit tests for intent classification, multi-task splitting, and time-to-task association.
3. Repository/Room tests for create/edit/delete/complete and migrations.
4. Scheduler tests for first occurrence, next recurrence, reboot reconciliation, and duplicate prevention.
5. Offline tests proving core flows work with networking disabled.
6. AI-provider tests using mocked responses and failures; no live secret required in CI.
7. Build, lint, test, package ID, version, and signing-certificate verification.
8. Manual device test checklist signed off before calling a build a candidate release.

## Mandatory regression scenarios

- «فردا ساعت ۱۰ برم نون بخرم ساعت ۱۴ هم برم سرکار» → two separate reminders.
- «فردا ساعت ۹ بانک، ساعت ۱۲ دکتر، ساعت ۵ خرید» → three separate reminders.
- «هر روز ساعت ۸ دارو بخورم» → recurring daily reminder, not one instance.
- «هر هفته چهارشنبه‌ها ساعت ۱۰ جلسه» → recurring Wednesday reminder.
- «هر سال روز تولدم را یادآوری کن» → yearly recurrence with clarification if the date is missing.
- «امروز چندشنبه است؟» → ordinary question; no reminder created.
- «ساعت ۱۰ الان چند ساعت دیگه میشه؟» → ordinary question; no reminder created.
- Disable internet → create, edit, list, and receive local reminders.
- Reboot device → future reminders remain scheduled.
- Deny exact-alarm permission → explain limitation and use supported fallback.

## Delivery sequence

Phase 1: baseline audit and product/architecture specification.
Phase 2: local data model, date engine, recurrence engine, and deterministic tests.
Phase 3: scheduler, receivers, reboot/time-change reconciliation, and device tests.
Phase 4: local intent parsing and multi-reminder extraction.
Phase 5: optional online AI provider and honest offline/online state.
Phase 6: UI, accessibility, and visual polish.
Phase 7: regression, security, signing, and side-by-side APK release.

## Decision log

- The existing app remains untouched on main.
- All Yadet 2 work happens on rebuild/yadet-v2-foundation until explicitly approved.
- No merging to main without the user's explicit approval.
- No production APK until quality gates pass.
- A new test application ID must be used so the old installed app and its local data remain intact.
