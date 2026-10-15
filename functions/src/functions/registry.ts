/**
 * Handler registry.
 *
 * One place lists the operations the platform exposes. `src/index.ts` binds these to
 * Firebase callables, and the tests call the same functions with an in-memory
 * DataStore — so what is tested is what is deployed.
 */

import { acceptInvitation, inviteCompanyMember, setMemberStatus, updateMemberRole } from "./members";
import { createCompany, updateCompanySettings } from "./company";
import { createCustomer, updateCustomer } from "./customers";
import { acceptRiderInvitation, createRider, createRiderInvitation, updateRider } from "./riders";
import { calculateDeliveryQuote, updateCompanyPricing } from "./pricing";
import { createOrder } from "./orders";
import { assignRider, recordDeliveryActuals, updateDeliveryStatus, updateOrder } from "./orderLifecycle";
import { createExpense, deleteExpense } from "./expenses";
import { getPublicTracking } from "./tracking";
import { dashboardMetricsSchema, getCompanyDashboardMetrics, rebuildDailyRollups } from "./reports";
import {
  getMySubscription,
  manageSubscription,
  recordOfflinePayment,
  revokeBillingBypass,
  setBillingBypass,
} from "./subscriptions";
import { getPlatformOverview, listCompanies, updatePlatformSettings } from "./admin";
import { registerDeviceToken } from "./notifications";

export const HANDLERS = {
  createCompany,
  updateCompanySettings,
  inviteCompanyMember,
  acceptInvitation,
  updateMemberRole,
  setMemberStatus,
  createCustomer,
  updateCustomer,
  createRider,
  updateRider,
  createRiderInvitation,
  acceptRiderInvitation,
  updateCompanyPricing,
  calculateDeliveryQuote,
  createOrder,
  updateOrder,
  assignRider,
  updateDeliveryStatus,
  recordDeliveryActuals,
  createExpense,
  deleteExpense,
  getPublicTracking,
  getCompanyDashboardMetrics,
  rebuildDailyRollups,
  getMySubscription,
  recordOfflinePayment,
  setBillingBypass,
  revokeBillingBypass,
  manageSubscription,
  listCompanies,
  getPlatformOverview,
  updatePlatformSettings,
  registerDeviceToken,
} as const;

export type HandlerName = keyof typeof HANDLERS;

export const HANDLER_NAMES = Object.keys(HANDLERS) as HandlerName[];

export { dashboardMetricsSchema };
