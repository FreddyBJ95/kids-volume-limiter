import type { PluginListenerHandle } from '@capacitor/core';

export type OutputRoute = 'speaker' | 'headphones' | 'bluetooth' | 'other';

/** Udgange, der kan have deres egen grænse. */
export type LimitRoute = 'speaker' | 'headphones' | 'bluetooth';

export interface RouteLimit {
  /** Højttalere er altid begrænset; hovedtelefoner og Bluetooth kun når forælderen slår det til. */
  enabled: boolean;
  /** Den valgte grænse i procent (10–100). */
  percent: number;
  /** Grænsen omregnet til enhedens lydstyrketrin (rundet ned). */
  index: number;
  effectivePercent: number;
}

export interface LimiterStatus {
  /** Forælderens indstilling: skal beskyttelsen være slået til? */
  enabled: boolean;
  /** Kører forgrundstjenesten lige nu? */
  serviceRunning: boolean;
  currentIndex: number;
  minIndex: number;
  maxIndex: number;
  currentPercent: number;
  /** Grænsen (trin) for den udgang, lyden går til lige nu. */
  limitIndex: number;
  effectiveLimitPercent: number;
  route: OutputRoute;
  routeLimited: boolean;
  /** Den udgang, hvis grænse forsiden viser: hovedtelefoner/Bluetooth, når de bruges, ellers højttalere. */
  limitRoute: LimitRoute;
  limits: Record<LimitRoute, RouteLimit>;
  volumeFixed: boolean;
  pinSet: boolean;
  onboardingDone: boolean;
  unlocked: boolean;
  unlockSecondsLeft: number;
}

export interface DeviceState {
  manufacturer: string;
  model: string;
  androidVersion: string;
  sdkInt: number;
  notificationsGranted: boolean;
  ignoringBatteryOptimizations: boolean;
  backgroundRestricted: boolean;
}

export interface CorrectionEntry {
  at: number;
  from: number;
  to: number;
  trigger: string;
  handlingMs: number;
  route: OutputRoute;
}

export interface Diagnostics {
  corrections: number;
  recent: CorrectionEntry[];
  serviceStartedAt: number;
  serviceStartReason: string;
  lastBootAt: number;
  lastBootStartOk: boolean;
  lastWatchdogAt: number;
}

export type SelfTestStatus = 'ok' | 'fail' | 'skip';

export interface SelfTestStep {
  id: string;
  label: string;
  status: SelfTestStatus;
  detail: string;
}

export interface SelfTestResult {
  ok: boolean;
  steps: SelfTestStep[];
  latencyMs: number | null;
}

export interface PinResult {
  ok: boolean;
  attemptsLeft: number;
  lockoutSeconds: number;
}

/** Fejlkoder, som det native plugin kan afvise med. */
export type LimiterErrorCode = 'LOCKED' | 'NO_PIN' | 'PIN_EXISTS' | 'INVALID_PIN' | 'INVALID_VALUE' | 'ERROR';

export interface VolumeLimiterPlugin {
  getStatus(): Promise<LimiterStatus>;
  getDeviceState(): Promise<DeviceState>;
  getDiagnostics(): Promise<Diagnostics>;

  createPin(options: { pin: string }): Promise<LimiterStatus>;
  verifyPin(options: { pin: string }): Promise<PinResult>;
  changePin(options: { pin: string }): Promise<void>;
  lock(): Promise<LimiterStatus>;

  setLimit(options: { route: LimitRoute; percent: number }): Promise<LimiterStatus>;
  setRouteLimitEnabled(options: { route: Exclude<LimitRoute, 'speaker'>; enabled: boolean }): Promise<LimiterStatus>;
  setEnabled(options: { enabled: boolean }): Promise<LimiterStatus>;
  completeOnboarding(): Promise<LimiterStatus>;

  requestNotificationPermission(): Promise<{ granted: boolean }>;
  requestIgnoreBatteryOptimizations(): Promise<{ ignoring: boolean }>;
  openAppSettings(): Promise<void>;

  runSelfTest(): Promise<SelfTestResult>;

  addListener(
    eventName: 'statusChanged',
    listenerFunc: (status: LimiterStatus) => void,
  ): Promise<PluginListenerHandle>;
  /** Appen er vendt tilbage til forgrunden, fx fra Androids indstillinger. */
  addListener(eventName: 'appResumed', listenerFunc: () => void): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}
