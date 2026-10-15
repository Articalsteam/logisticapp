import { defineConfig } from "vitest/config";

/**
 * Two test projects:
 *  - `unit`  : pure logic (pricing engine, entitlement, status transitions, money,
 *              and function handlers driven by an in-memory DataStore fake).
 *              Runs anywhere, no emulator required.
 *  - `rules` : Firebase Emulator Suite tests for firestore.rules / storage.rules.
 *              Requires `firebase emulators:exec` (see npm run test:rules) and a JDK.
 */
export default defineConfig({
  test: {
    projects: [
      {
        test: {
          name: "unit",
          environment: "node",
          include: ["src/**/*.test.ts", "tests/unit/**/*.test.ts"],
          globals: false,
        },
      },
      {
        test: {
          name: "rules",
          environment: "node",
          include: ["tests/rules/**/*.test.ts"],
          testTimeout: 30_000,
          hookTimeout: 60_000,
          fileParallelism: false,
        },
      },
    ],
  },
});
