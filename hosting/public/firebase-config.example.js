/**
 * Copy this file to `hosting/public/firebase-config.js` and fill in the web app
 * configuration from Firebase console → Project settings → Your apps → Web.
 *
 * The real file is git-ignored on purpose: it names your project and carries the
 * public web API key. `track.html` reads `window.ARTICALS_FIREBASE_CONFIG` and, when
 * the file is missing, states that tracking is not configured rather than showing a
 * broken form.
 *
 * The web API key is not a secret in the "can spend your money" sense — it
 * identifies the project — but access is controlled by App Check, the security
 * rules and the callable's own rate limits, not by hiding it.
 */
window.ARTICALS_FIREBASE_CONFIG = {
  apiKey: "REPLACE_WITH_WEB_API_KEY",
  authDomain: "your-project-id.firebaseapp.com",
  projectId: "your-project-id",
  storageBucket: "your-project-id.appspot.com",
  messagingSenderId: "000000000000",
  appId: "1:000000000000:web:0000000000000000000000",
};

// Must match the region the callables are deployed to (functions/src/index.ts).
window.ARTICALS_FIREBASE_REGION = "europe-west1";
