# Connecting real WhatsApp (Meta Cloud API)

No BSP. Siletry talks to Meta's WhatsApp Cloud API directly.

- **Your Meta app** (Techynt / Siletry): the central Siletry number for login codes, doctor alerts and NEXT / LATE commands. Its free test number is also for demos.
- **Each pilot clinic**: their own Meta Business account and their own app, set up together with them, then connected in Siletry → Setup → WhatsApp (or WhatsApp numbers).
- **Later, as a Meta Tech Provider** (needs your business verification): one app for everyone and a real "Connect with Meta" button. The same database fields get filled automatically.

Meta renames menus often. If a label below doesn't match, look for the nearest one.

---

## Part A: your Siletry app (do once)

1. Go to **developers.facebook.com → My Apps → Create app**. Choose the **Business** type (or "Other → Business"), and pick your Techynt business portfolio.
2. In the app, **add the WhatsApp product**. Meta creates a test WhatsApp Business Account and a **test phone number**.
3. On **WhatsApp → API Setup** note:
   - **Phone number ID** → `META_ADMIN_PHONE_NUMBER_ID`
   - the test number itself → `META_ADMIN_DISPLAY_NUMBER`
   - Under "To", **add up to 5 phone numbers** that may receive messages (yours, co-founder, demo clinic owner). Each gets a code to confirm.
4. **Permanent token** (the one on the API Setup page expires in 24 hours):
   Business Settings → Users → **System users** → Add (Admin) → **Assign assets**: your app (full control) and the WhatsApp account → **Generate token** for your app with `whatsapp_business_messaging` and `whatsapp_business_management` → `META_ADMIN_ACCESS_TOKEN`.
5. **App secret**: App settings → Basic → App secret → `META_APP_SECRET`.
6. **Webhook**: WhatsApp → Configuration → Webhook → Edit
   - Callback URL: `https://siletry-backend-new-production.up.railway.app/api/webhooks/meta`
   - Verify token: any random string → also set as `META_VERIFY_TOKEN` in Railway **before** you click Verify
   - Then **Subscribe** to the `messages` field.
7. **Templates in your account** (WhatsApp Manager → Message templates → Create):
   - `siletry_login_code`: category **Authentication**, English, "Copy code" button. Meta writes the text itself.
   - `siletry_staff_alert`: category **Utility**, English, body:
     `Siletry update for {{1}}: {{2}} Reply to this message to respond.`
     Example values: `Nandini Clinic` and `Imran Sheikh (+91 98450 22114) reported chest pain. Please call them now.`

### Railway variables (backend)

```
CREDENTIALS_KEY=<long random string, set once, never change>
META_GRAPH_VERSION=v22.0          # use the version shown in your app dashboard if newer
META_VERIFY_TOKEN=<same as in the webhook settings>
META_APP_SECRET=<your app secret>
META_ADMIN_PHONE_NUMBER_ID=<phone number ID>
META_ADMIN_ACCESS_TOKEN=<system user token>
META_ADMIN_DISPLAY_NUMBER=<the number, e.g. 15551234567>
DEV_TOOLS=true                     # simulator + test mode while you test. FALSE before real clinics.
```

Generate random strings with `openssl rand -hex 32`.

### Demo with the test number

Connect the test number as a clinic, so a real phone can book:
Siletry → WhatsApp numbers → paste the **same** test number's Phone number ID, **WhatsApp Business Account ID** (API Setup page) and the system user token. Leave App secret empty (it's your app). Then from one of the 5 allowed phones, message the test number "hi".

> The test number can be either the Siletry admin number **or** a demo clinic, not both. For demos, use it as the clinic and leave `META_ADMIN_*` empty (login codes then show in test mode).

---

## Part B: each pilot clinic (about 30–45 minutes, together with the owner)

On the owner's laptop, logged in to **their** Facebook account. You guide; they click. You never need their password or admin access.

1. **Meta Business account**: business.facebook.com → create one for the clinic if they don't have it (clinic legal name, address exactly as on their documents).
2. **App**: developers.facebook.com → Create app (Business) in the clinic's business → add **WhatsApp**.
3. **Add their number**: WhatsApp → API Setup → **Add phone number** → display name = clinic name → verify with the SMS/call code.
   - A number already on the WhatsApp Business **app** usually has to be moved off the app (or use a new number). Check Meta's current rules for keeping the app on the same number ("coexistence"); it may need a partner. Tell the clinic before you start.
   - Set a **two-step PIN** if asked, and note it (Siletry can register the number with it).
4. **Permanent token**: Business Settings → System users → Add → assign the app and the WhatsApp account → Generate token (`whatsapp_business_messaging`, `whatsapp_business_management`).
5. **Webhook** (in the clinic's app): same callback URL and verify token as Part A, subscribe to `messages`.
6. **App secret**: App settings → Basic.
7. In **Siletry → Setup → WhatsApp → Connect WhatsApp**, paste: Phone number ID, WhatsApp Business Account ID, token, app secret, PIN (if any).
   Siletry checks them with Meta, subscribes to messages, and sends its 9 templates for approval.
8. **Templates** (Siletry → Message templates → Refresh status) usually get approved in minutes to a day. Until then, replies work but reminders and follow-ups to patients who haven't written in 24 hours will fail.
9. **Payment method**: in the clinic's WhatsApp Manager, add a card for Meta's per-message charges (reminders/templates). Replies to patients within 24 hours are free.
10. Send "hi" to the clinic number from your phone and check it appears in Siletry's Inbox.

The token belongs to the clinic's business. They can remove it any time (Business Settings → System users).

---

## Limits to know

- **Unverified business**: Meta caps how many new people you can message first per day, and how many numbers you can register. Verification (clinic's, and yours for Tech Provider) lifts these.
- **24-hour rule**: free text only within 24 hours of the patient's last message; otherwise approved templates, which Meta charges per message.
- **Quality rating**: if patients block or report the number, Meta limits it. Only message people who wrote to the clinic or agreed to reminders.
