import { WebPlugin } from '@capacitor/core';
import type {
  CorrectionEntry,
  DeviceState,
  Diagnostics,
  LimitRoute,
  LimiterStatus,
  OutputRoute,
  PinResult,
  RouteLimit,
  SelfTestResult,
  VolumeLimiterPlugin,
} from './definitions';

const ROUTE_CYCLE: LimitRoute[] = ['speaker', 'headphones', 'bluetooth'];

/**
 * Simulering til browseren (kun til udvikling af brugerfladen).
 * Taster: Pil op/ned = tablettens lydstyrkeknapper, H = skift højttaler → hovedtelefoner → Bluetooth.
 * Tilføj ?demo=klar til adressen for at springe opsætningen over (PIN 1234).
 */
export class VolumeLimiterWeb extends WebPlugin implements VolumeLimiterPlugin {
  private max = 15;
  // Android har en separat medielydstyrke for hver udgang.
  private volumes: Record<OutputRoute, number> = { speaker: 9, headphones: 10, bluetooth: 8, other: 8 };
  private enabled = false;
  private serviceRunning = false;
  private limits: Record<LimitRoute, { enabled: boolean; percent: number }> = {
    speaker: { enabled: true, percent: 40 },
    headphones: { enabled: false, percent: 40 },
    bluetooth: { enabled: false, percent: 40 },
  };
  private onboardingDone = false;
  private pin: string | null = null;
  private failed = 0;
  private lockoutUntil = 0;
  private unlockedUntil = 0;
  private route: OutputRoute = 'speaker';
  private recent: CorrectionEntry[] = [];
  private corrections = 0;

  constructor() {
    super();
    if (new URLSearchParams(location.search).get('demo') === 'klar') {
      this.pin = '1234';
      this.enabled = true;
      this.serviceRunning = true;
      this.onboardingDone = true;
      this.volumes.speaker = 5;
    }
    window.addEventListener('keydown', (e) => {
      if (e.target instanceof HTMLInputElement) return;
      if (e.key === 'ArrowUp') this.pressVolume(1);
      else if (e.key === 'ArrowDown') this.pressVolume(-1);
      else if (e.key.toLowerCase() === 'h') {
        const i = ROUTE_CYCLE.indexOf(this.route as LimitRoute);
        this.route = ROUTE_CYCLE[(i + 1) % ROUTE_CYCLE.length];
        this.emit();
        this.enforce('lydudgang');
      }
    });
  }

  private pressVolume(delta: number) {
    this.volumes[this.route] = Math.min(this.max, Math.max(0, this.volumes[this.route] + delta));
    this.emit();
    setTimeout(() => this.enforce('knap/lydstyrke'), 40);
  }

  private indexFor(percent: number) {
    return Math.max(1, Math.floor((this.max * percent) / 100));
  }

  private routeLimit(): { enabled: boolean; percent: number } {
    return this.route === 'other' ? { enabled: false, percent: 100 } : this.limits[this.route];
  }

  private enforce(trigger: string) {
    const limit = this.routeLimit();
    if (!this.enabled || !limit.enabled) return;
    const index = this.indexFor(limit.percent);
    const volume = this.volumes[this.route];
    if (volume <= index) return;
    this.recent.unshift({ at: Date.now(), from: volume, to: index, trigger, handlingMs: 2, route: this.route });
    this.recent = this.recent.slice(0, 20);
    this.corrections++;
    this.volumes[this.route] = index;
    this.emit();
  }

  private status(): LimiterStatus {
    const limit = this.routeLimit();
    const limitIndex = this.indexFor(limit.percent);
    const volume = this.volumes[this.route];
    const limits = {} as Record<LimitRoute, RouteLimit>;
    for (const r of ROUTE_CYCLE) {
      const index = this.indexFor(this.limits[r].percent);
      limits[r] = { ...this.limits[r], index, effectivePercent: Math.round((index * 100) / this.max) };
    }
    return {
      enabled: this.enabled,
      serviceRunning: this.serviceRunning,
      currentIndex: volume,
      minIndex: 0,
      maxIndex: this.max,
      currentPercent: Math.round((volume * 100) / this.max),
      limitIndex,
      effectiveLimitPercent: Math.round((limitIndex * 100) / this.max),
      route: this.route,
      routeLimited: limit.enabled,
      limitRoute: this.route === 'other' ? 'speaker' : this.route,
      limits,
      volumeFixed: false,
      pinSet: this.pin !== null,
      onboardingDone: this.onboardingDone,
      unlocked: Date.now() < this.unlockedUntil,
      unlockSecondsLeft: Math.max(0, Math.ceil((this.unlockedUntil - Date.now()) / 1000)),
    };
  }

  private emit() {
    this.notifyListeners('statusChanged', this.status());
  }

  private unlock() {
    this.unlockedUntil = Date.now() + 3 * 60_000;
  }

  private requireUnlocked() {
    if (Date.now() >= this.unlockedUntil) throw Object.assign(new Error('Indtast PIN-koden først'), { code: 'LOCKED' });
    this.unlock();
  }

  async getStatus() {
    return this.status();
  }

  async getDeviceState(): Promise<DeviceState> {
    return {
      manufacturer: 'Browser',
      model: 'Simulering',
      androidVersion: '–',
      sdkInt: 0,
      notificationsGranted: true,
      ignoringBatteryOptimizations: false,
      backgroundRestricted: false,
    };
  }

  async getDiagnostics(): Promise<Diagnostics> {
    return {
      corrections: this.corrections,
      recent: this.recent,
      serviceStartedAt: this.serviceRunning ? Date.now() - 3_600_000 : 0,
      serviceStartReason: 'slået til i appen',
      lastBootAt: 0,
      lastBootStartOk: false,
      lastWatchdogAt: 0,
    };
  }

  async createPin({ pin }: { pin: string }) {
    if (this.pin) throw Object.assign(new Error('Der findes allerede en PIN-kode'), { code: 'PIN_EXISTS' });
    if (!/^\d{4,6}$/.test(pin)) throw Object.assign(new Error('PIN-koden skal være 4-6 cifre'), { code: 'INVALID_PIN' });
    this.pin = pin;
    this.unlock();
    return this.status();
  }

  async verifyPin({ pin }: { pin: string }): Promise<PinResult> {
    await new Promise((r) => setTimeout(r, 250));
    const now = Date.now();
    if (now < this.lockoutUntil) return { ok: false, attemptsLeft: 0, lockoutSeconds: Math.ceil((this.lockoutUntil - now) / 1000) };
    if (pin === this.pin) {
      this.failed = 0;
      this.unlock();
      this.emit();
      return { ok: true, attemptsLeft: 5, lockoutSeconds: 0 };
    }
    this.failed++;
    if (this.failed >= 5) this.lockoutUntil = now + 30_000 * 2 ** Math.min(this.failed - 5, 5);
    return {
      ok: false,
      attemptsLeft: Math.max(0, 5 - this.failed),
      lockoutSeconds: this.failed >= 5 ? Math.ceil((this.lockoutUntil - now) / 1000) : 0,
    };
  }

  async changePin({ pin }: { pin: string }) {
    this.requireUnlocked();
    this.pin = pin;
  }

  async lock() {
    this.unlockedUntil = 0;
    return this.status();
  }

  async setLimit({ route, percent }: { route: LimitRoute; percent: number }) {
    this.requireUnlocked();
    this.limits[route].percent = percent;
    this.enforce('grænse ændret');
    return this.status();
  }

  async setRouteLimitEnabled({ route, enabled }: { route: Exclude<LimitRoute, 'speaker'>; enabled: boolean }) {
    this.requireUnlocked();
    this.limits[route].enabled = enabled;
    this.enforce('grænse ændret');
    return this.status();
  }

  async setEnabled({ enabled }: { enabled: boolean }) {
    if (!enabled) this.requireUnlocked();
    this.enabled = enabled;
    this.serviceRunning = enabled;
    this.enforce('beskyttelse slået til');
    return this.status();
  }

  async completeOnboarding() {
    this.onboardingDone = true;
    return this.status();
  }

  async requestNotificationPermission() {
    return { granted: true };
  }

  async requestIgnoreBatteryOptimizations() {
    return { ignoring: true };
  }

  async openAppSettings() {
    alert('Her åbnes Androids app-indstillinger på tabletten.');
  }

  async runSelfTest(): Promise<SelfTestResult> {
    await new Promise((r) => setTimeout(r, 600));
    const s = this.status();
    return {
      ok: s.enabled,
      latencyMs: s.enabled ? 42 : null,
      steps: [
        { id: 'service', label: 'Overvågning i baggrunden', status: s.enabled ? 'ok' : 'fail', detail: s.enabled ? 'Forgrundstjenesten kører.' : 'Beskyttelsen er slået fra.' },
        { id: 'route', label: 'Lydudgang', status: 'ok', detail: 'Indbyggede højttalere – begrænses.' },
        { id: 'control', label: 'Appen kan ændre lydstyrken', status: 'ok', detail: `Lydstyrken blev ændret og sat tilbage (trin ${s.currentIndex} af ${s.maxIndex}).` },
        { id: 'auto', label: 'Automatisk sænkning', status: s.enabled ? 'ok' : 'skip', detail: s.enabled ? 'Lydstyrken blev sænket efter 42 ms (udløst af: knap/lydstyrke).' : 'Kan ikke testes, før ovenstående virker.' },
      ],
    };
  }
}
