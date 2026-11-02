# Firebase setup (phase 2 relay)

The relay between your computer and your watch runs in **your own** Firebase project. The design stays on the free **Spark** plan: no Cloud Functions, no billing account.

You need a Google account. It takes about 10 minutes. Firebase console labels change from time to time; the steps below describe what to look for.

## 1. Create the project

1. Go to <https://console.firebase.google.com> and click **Create a project**.
2. Name it, for example `wristle-relay`. Google Analytics is not needed: turn it off.
3. Check that the plan shown is **Spark (free)**. Don't upgrade.

## 2. Firestore

1. **Build → Firestore Database → Create database**.
2. Location: a region near you, for example `eur3 (europe-west)`. It can't be changed later.
3. Start in **production mode**.
4. Open the **Rules** tab, replace everything with the contents of [`relay/firestore.rules`](../relay/firestore.rules) and click **Publish**.

## 3. Anonymous sign-in for the watch

**Build → Authentication → Get started → Sign-in method → Anonymous → Enable → Save.**

The watch signs in without an account; the rules only let it touch its own documents.

## 4. Register the watch app

1. **Project settings** (gear icon) → **General** → **Your apps** → the **Android** icon.
2. Android package name: `dev.ayoub.wristle`. Nickname: `Wristle`. Skip the SHA-1.
3. Click **Register app**. Download `google-services.json` but **do not put it in the repo**; you only need four values from it.
4. Add these lines to `local.properties` in the project root (git-ignored):

   ```properties
   firebase.projectId=<project_info.project_id>
   firebase.appId=<client[0].client_info.mobilesdk_app_id>
   firebase.apiKey=<client[0].api_key[0].current_key>
   firebase.senderId=<project_info.project_number>
   ```

   Skip the remaining steps of the wizard (they assume the Google Services Gradle plugin, which Wristle doesn't use).

These values identify the project; they are not secrets in the way a service account key is. They still stay out of the public repo.

## 5. Give the bridge access

1. **Project settings → Service accounts → Firebase Admin SDK → Generate new private key**.
2. A JSON file downloads. **This file is a real secret**: it gives full access to the project. Never commit or share it.
3. On your computer:

   ```bash
   cd bridge
   npm run build
   node dist/cli.js relay setup <path-to-the-downloaded-file.json>
   ```

   The bridge copies it into its config folder (only readable by you) and checks that it can reach Firestore. You can delete the downloaded file afterwards.

## 6. Run the relay test (P2-M2a)

1. Build and install Wristle on the watch with the new `local.properties` values.
2. On the watch: **Settings → Relay test**. It signs in, registers for push messages and shows **Ready**.
3. On the computer: `node dist/cli.js relay test`. The bridge finds the watch, sends it a push, and waits for the watch to answer through Firestore. It prints the round-trip time.
4. Try it once on Wi-Fi and once with the watch on LTE (or away from your Wi-Fi), if your watch has LTE.

## 7. Pair the watch with your computer (P2-M2b)

1. If you set up Firestore before pairing existed: publish the latest [`relay/firestore.rules`](../relay/firestore.rules) again (**Rules** tab → paste → **Publish**).
2. On the watch: open **Code** (the right half of the start screen). It shows a code like `ABCD-EFGH`.
3. On the computer: `node dist/cli.js pair ABCD-EFGH`.
4. Both screens now show the same 6-digit security code. Check that they match, then confirm on the watch (✓) and on the computer (`y`). If they differ, cancel: someone else's key is in between.
5. `node dist/cli.js ping` sends an encrypted ping; the watch opens it, answers, and counts it under **Code**.

From here on Firestore only holds public keys and sealed messages: the keys to open them never leave the watch and the computer.

## Free-tier limits

The Spark plan includes, per day, 50,000 Firestore reads and 20,000 writes. Push messages (FCM) are free. A busy day of approving and answering from the watch stays far below that.
