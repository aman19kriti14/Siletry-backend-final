# Running clinics on Gupshup

Siletry talks to WhatsApp through Gupshup's partner passthrough API: the same Meta-format messages,
sent to `partner.gupshup.io`. Clinics connected directly on Meta (see META_SETUP.md) keep working.

## 1. Railway variables (once)

```
GUPSHUP_PARTNER_EMAIL=<email you log in to the Gupshup Partner Portal with>
GUPSHUP_PARTNER_SECRET=<client secret from the Partner Portal>
GUPSHUP_WEBHOOK_SECRET=<openssl rand -hex 24>
PUBLIC_BASE_URL=https://siletry-backend-final-production.up.railway.app
OPS_API_KEY=<openssl rand -hex 24>          # for the /api/ops calls below

# Production safety (the app refuses to boot in production without these)
JWT_SECRET=<openssl rand -hex 32>
CREDENTIALS_KEY=<openssl rand -hex 32>      # set ONCE. Changing it breaks stored WhatsApp tokens
DEV_TOOLS=false
SEED_DEMO=false
ALLOWED_ORIGINS=<your current value, minus any localhost entries>
```

Optional: run Siletry's own number (login codes, doctor alerts, NEXT / LATE) on Gupshup too:

```
GUPSHUP_ADMIN_APP_ID=<app id>
GUPSHUP_ADMIN_APP_TOKEN=<app token>
GUPSHUP_ADMIN_DISPLAY_NUMBER=<number with country code>
```

That app needs two templates, created in the Gupshup portal: `siletry_login_code` (Authentication,
copy-code button) and `siletry_staff_alert` (Utility):
`Siletry update for {{1}}: {{2}} Reply to this message to respond.`
Also give it the same webhook as the clinics (step 2c).

## 2. Each clinic (about 10 minutes)

a. The clinic signs up in Siletry (app.siletry.com) and finishes the setup wizard.

b. In the Gupshup Partner Portal: create an app for the clinic and send the owner the onboarding
   (embedded signup) link. They log in with their Facebook account and verify their number.
   Note the **App ID**.

c. Connect it to Siletry. Find the clinic's id first:

```bash
curl -s https://<backend>/api/ops/clinics -H "X-Ops-Key: $OPS_API_KEY"

curl -s -X POST https://<backend>/api/ops/clinics/<CLINIC_ID>/gupshup \
  -H "X-Ops-Key: $OPS_API_KEY" -H "Content-Type: application/json" \
  -d '{"appId":"<GUPSHUP_APP_ID>","whatsappNumber":"91XXXXXXXXXX"}'
```

   This fetches the app token, subscribes the app's webhook (v3, Meta format) and submits all
   nine templates. Read the `warnings` in the reply. If `webhookSubscribed` is false, add the
   subscription by hand in the portal: **version v3 (Meta format)**, events: messages + sent,
   delivered, read, failed, URL:
   `https://<backend>/api/webhooks/gupshup/<GUPSHUP_WEBHOOK_SECRET>`

d. Templates usually approve in minutes to a few hours. Check:

```bash
curl -s -X POST https://<backend>/api/ops/clinics/<CLINIC_ID>/templates/sync -H "X-Ops-Key: $OPS_API_KEY"
```

   Until they're approved, replies to patients work (they messaged first), but reminders and
   confirmations sent later than 24 hours after the patient's last message don't.

e. Test from your phone: message the clinic's number "hi", book a slot, scan the check-in QR.

## 3. When they pay

```bash
curl -s -X POST https://<backend>/api/ops/clinics/<CLINIC_ID>/plan \
  -H "X-Ops-Key: $OPS_API_KEY" -H "Content-Type: application/json" -d '{"plan":"ACTIVE"}'
```

`{"plan":"TRIAL","trialDays":7}` extends a trial, `{"plan":"PAUSED"}` turns the bot off (messages go to the inbox).

## If something breaks

- Bot errors never leave a patient hanging: the chat goes to the clinic's inbox as "Needs you", the
  patient is told the team will reply, and `OPS_ALERT_TO` gets a WhatsApp alert (at most one per
  error type every 10 minutes).
- "Gupshup rejected the app token": run step 2c again for that clinic.
- Messages arrive in Gupshup but not in Siletry: check the subscription URL ends with the exact
  `GUPSHUP_WEBHOOK_SECRET`, and that the subscription is v3. Railway logs show
  "Rejected Gupshup webhook with a wrong key" on a mismatch.
