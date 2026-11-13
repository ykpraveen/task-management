export { createApiClients, newIdempotencyKey, type ApiClientOptions, type ApiClients } from './client';
export * from './ids';
export type * as TaskApi from './generated/task-api';
export type * as NotificationsApi from './generated/notifications';

import type { components as TaskComponents } from './generated/task-api';
import type { components as NotificationComponents } from './generated/notifications';

export type Task = TaskComponents['schemas']['Task'];
export type TaskStatus = TaskComponents['schemas']['TaskStatus'];
export type Priority = TaskComponents['schemas']['Priority'];
export type UserSummary = TaskComponents['schemas']['UserSummary'];
export type Problem = TaskComponents['schemas']['Problem'];
export type FieldError = TaskComponents['schemas']['FieldError'];
export type Notification = NotificationComponents['schemas']['Notification'];
