# Siletry backend

Spring Boot 3.3, Java 21, PostgreSQL. Hibernate creates the tables itself (`ddl-auto=update`).
WhatsApp is mocked for now: every message is still stored, so the web app shows exactly what a patient would get.

## Run locally

```bash
docker compose up -d          # Postgres on localhost:5432 (or use your own)
mvn spring-boot:run           # http://localhost:8080
```

On first boot with an empty database it seeds **Nandini Clinic** from the designs:

| Login | Password | Role |
|---|---|---|
| demo@siletry.com | demo1234 | Owner (Dr. Anitha Rao) |
| desk@siletry.com | demo1234 | Receptionist |

Turn seeding off with `SEED_DEMO=false`.

## Deploy on Railway

1. New project → Deploy from GitHub repo (this folder). Railway picks up the `Dockerfile`.
2. Add a **PostgreSQL** database to the project.
3. In the backend service → **Variables**, add:
   ```
   PGHOST=${{Postgres.PGHOST}}
   PGPORT=${{Postgres.PGPORT}}
   PGDATABASE=${{Postgres.PGDATABASE}}
   PGUSER=${{Postgres.PGUSER}}
   PGPASSWORD=${{Postgres.PGPASSWORD}}
   JWT_SECRET=<long random string, 32+ chars>
   ALLOWED_ORIGINS=https://your-frontend-domain,http://localhost:5173
   WHATSAPP_WEBHOOK_SECRET=<random string>
   ```
4. Deploy. Health check: `GET /actuator/health`.

## Before the first real clinic

- **Switch to Flyway.** `ddl-auto=update` only adds tables and columns. It never renames, drops or migrates data. Generate a baseline from the current schema, add Flyway, set `ddl-auto=validate`.
- **Set `DEV_TOOLS=false`.** With it on, login codes can appear on screen and the simulator is open.
- **Set `CREDENTIALS_KEY`** once. Clinic WhatsApp tokens are encrypted with it.
- **Connect WhatsApp** per clinic (META_SETUP.md). Templates are submitted to Meta automatically.

## How it's organised

```
auth/         signup, login (JWT), staff accounts
clinic/       clinic settings, automations, WhatsApp connection, usage vs cap
doctor/       doctors, weekly sessions, leave, booking rules
appointment/  slot engine, holds, booking/reschedule/cancel, Appointments screen
queue/        check-in, walk-ins, tokens, Call next, announce delay
patient/      patients list, profile
followup/     follow-ups (Siletry messages the patient on the due date)
messaging/    inbound pipeline, bot, emergency detector, inbox, notifications, webhooks
messaging/gateway/  WhatsAppGateway, Meta Cloud API gateway, mock, router (per clinic, simulator always mocked)
qr/           QR codes (booking / check-in) with wa.me links and PNGs
template/     WhatsApp message templates
overview/     Overview page numbers
jobs/         reminders, follow-ups, hold cleanup, stale queue nudges
dev/          mock WhatsApp panel (only when WHATSAPP_PROVIDER=mock)
seed/         demo data
```

Every table has a `clinic_id`. Services always take the clinic from the logged-in user (`CurrentUser.clinicId()`).

### Rules that matter

- **No double booking.** `appointment.slot_key` (`doctorId|start`) is unique while an appointment holds a slot and `null` once cancelled or a no-show. Two bookings for the same slot can't both save.
- **Held by Siletry.** When the bot offers slots on WhatsApp they're held for 10 minutes (`slot_hold`). Staff see them as `HELD` and can't book them.
- **24-hour window.** Replies within 24 hours of the patient's last message are free text. Anything later goes out as a template and counts toward the clinic's monthly cap (`clinic.message_cap`).
- **Emergencies never wait.** Chest pain, breathing trouble and similar messages get an instant "call 108" reply, the doctors are alerted on their own phones, and the thread goes to the top of Needs you. Crisis messages get the Tele-MANAS helpline (14416).
- **STOP** opts the patient out of business-initiated messages until they write again.

## API

All endpoints except auth and webhooks need `Authorization: Bearer <token>`.

### Auth & staff
| Method | Path | |
|---|---|---|
| POST | `/api/auth/signup/start` | name, email, phone, clinicName, doctorCount → sends a WhatsApp code, returns `ticket` (and `devCode` in mock mode) |
| POST | `/api/auth/signup/verify` | ticket, code → creates the clinic, returns token |
| POST | `/api/auth/login` | identifier (email or mobile), password, remember → token (30 days if remember) |
| POST | `/api/auth/otp/request` | identifier → WhatsApp code for login |
| POST | `/api/auth/otp/verify` | ticket, code, remember → token |
| POST | `/api/auth/otp/resend` | ticket |
| GET / POST | `/api/auth/password` | has a password? / set or change it |
| POST | `/api/auth/signup` | password signup for API clients |
| GET | `/api/auth/me` | |
| GET / POST | `/api/staff` | list / add staff (owner) |
| POST | `/api/staff/{id}/deactivate` · `/activate` | |

### Clinic setup wizard
| Method | Path | |
|---|---|---|
| GET | `/api/setup` | everything the wizard needs, including the current step |
| PUT | `/api/setup/clinic` | name, clinicType, city, address, landmark, languages |
| PUT | `/api/setup/doctors` | doctors [{id?, name, speciality, fee, slotMinutes}], days, morning {start,end}, evening {start,end} |
| PUT | `/api/setup/whatsapp` | choice (CURRENT/NEW), number |
| POST | `/api/setup/whatsapp/connect` | mock mode only: marks the number connected |
| PUT | `/api/setup/automations` | reminders, followups, delayAnnouncements, googleReviews |
| POST | `/api/setup/test-message` | sends a test to the owner's WhatsApp |
| POST | `/api/setup/go-live` | |

### Overview
| GET | `/api/overview?from=&to=` | defaults to this week vs last week: messages answered, bookings (and % without staff), revenue booked, no-show rate, bars per day, needs-a-human list, languages |

### Appointments
| Method | Path | |
|---|---|---|
| GET | `/api/appointments?tab=today\|week\|noshows\|cancelled&date=&doctorId=&status=&bookedBy=` | tab counts + rows |
| POST | `/api/appointments` | Book a visit: patientId (or newPatient {name, phone}), doctorId, startAt, durationMinutes, reason, frontDeskNote, sendConfirmation, remindEveningBefore, followUpId |
| GET | `/api/appointments/confirmation-preview?patientId=&doctorId=&startAt=` | "Patient will receive" preview |
| PATCH | `/api/appointments/{id}` | reason, frontDeskNote, emergency, remindEveningBefore |
| POST | `/api/appointments/{id}/reschedule` | startAt, doctorId?, notifyPatient |
| POST | `/api/appointments/{id}/cancel` | reason, notifyPatient |
| POST | `/api/appointments/{id}/no-show` | |

### Queue (Appointments → Today)
| Method | Path | |
|---|---|---|
| GET | `/api/queue` | every doctor's queue: current, waiting (position, ETA), average consult time, stale flag |
| GET | `/api/queue/doctors/{doctorId}` | |
| POST | `/api/queue/doctors/{doctorId}/call-next` | marks current seen, calls next, messages them |
| POST | `/api/queue/doctors/{doctorId}/announce-delay` | minutes, note |
| POST | `/api/queue/check-in/{appointmentId}` | |
| POST | `/api/queue/walk-in` | patientId or name+phone, doctorId, reason, emergency |
| POST | `/api/queue/appointments/{id}/done` · `/skip` · `/move-to-front` | |

### Doctors & availability
| Method | Path | |
|---|---|---|
| GET / POST | `/api/doctors` | |
| GET / PATCH | `/api/doctors/{id}` | includes bookingRules |
| PUT | `/api/doctors/{id}/hours` | `{ sessions: [{day: "MONDAY", start: "09:30", end: "13:00"}, ...] }` → also returns appointments now outside the hours |
| GET / POST | `/api/doctors/{id}/leave` | |
| DELETE | `/api/doctors/{id}/leave/{leaveId}` | |
| GET | `/api/doctors/{id}/slots?date=` | FREE / HELD / BOOKED / BLOCKED |
| GET | `/api/doctors/{id}/days?from=&days=7` | day strip with free counts |

### Patients
| Method | Path | |
|---|---|---|
| GET | `/api/patients?q=&page=&size=` | |
| GET / PATCH | `/api/patients/{id}` | full profile: next appointment, stats, details, follow-ups, visits |
| POST | `/api/patients` | |
| GET | `/api/search?q=` | header search |
| GET / POST | `/api/follow-ups` | `?patientId=` or due within 7 days |
| PATCH | `/api/follow-ups/{id}` | |

### Inbox
| Method | Path | |
|---|---|---|
| GET | `/api/inbox?tab=needs\|all` | |
| GET | `/api/inbox/{id}` | thread, marks read |
| GET | `/api/inbox/{id}/messages?after=` | polling |
| POST | `/api/inbox/{id}/take-over` · `/hand-back` · `/resolve` | |
| POST | `/api/inbox/{id}/reply` | text |

### Booking flow, templates, settings
| Method | Path | |
|---|---|---|
| GET / POST | `/api/qr` | QR codes with wa.me links |
| GET | `/api/qr/{id}/image.png?size=600&access_token=` | printable PNG |
| GET | `/api/templates` | Siletry's templates and their Meta status |
| GET / PATCH | `/api/clinic` | settings + automations |
| POST | `/api/clinic/whatsapp/connect` | number (mock connect) |
| GET | `/api/clinic/usage` | this month vs cap |

### Simulator (`DEV_TOOLS=true` only, always mocked)
| Method | Path | |
|---|---|---|
| POST | `/api/dev/whatsapp/patient-message` | phone, name, text → Siletry's replies |
| POST | `/api/dev/whatsapp/scan` | phone, name, qrId or code → simulates a QR scan |
| POST | `/api/dev/whatsapp/admin-message` | phone, text (e.g. "next") as a doctor/receptionist |
| GET | `/api/dev/whatsapp/thread?phone=` | |
| POST | `/api/dev/jobs/reminders` · `/api/dev/jobs/follow-ups` | run jobs now |

### Webhooks
| GET / POST | `/api/webhooks/meta` | Meta Cloud API webhook (verify token + X-Hub-Signature-256). Routes the Siletry number to staff commands (NEXT, QUEUE, TODAY, LATE 30), every other number to its clinic |
| POST | `/api/webhooks/whatsapp` · `/admin` | simple JSON webhook for testing, `DEV_TOOLS=true` only, header `X-Siletry-Webhook-Secret` |

### WhatsApp connection
| POST | `/api/setup/whatsapp/credentials`, `/api/clinic/whatsapp/credentials` | phoneNumberId, businessAccountId, accessToken, appSecret?, pin? → checked with Meta, stored encrypted, templates submitted |
| POST | `/api/clinic/whatsapp/disconnect` | |
| POST | `/api/templates/submit-all` · `/api/templates/sync` | send missing templates to Meta / refresh approval status |

## Try the bot in 30 seconds

```bash
TOKEN=$(curl -s localhost:8080/api/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"demo@siletry.com","password":"demo1234"}' | jq -r .token)

# A new patient scans the poster QR
curl -s localhost:8080/api/dev/whatsapp/scan -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210","name":"Ravi","code":"BK-POSTER"}' | jq

# Taps a day, then a time (use the button labels from the previous reply)
curl -s localhost:8080/api/dev/whatsapp/patient-message -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"phone":"9876543210","text":"Tomorrow"}' | jq
```
