import { VolumeLimiter, type LimiterStatus } from './plugin';

type Listener = (status: LimiterStatus) => void;

let current: LimiterStatus | null = null;
const listeners = new Set<Listener>();

function publish(status: LimiterStatus) {
  current = status;
  listeners.forEach((fn) => fn(status));
}

export function status(): LimiterStatus {
  if (!current) throw new Error('Status er ikke indlæst endnu');
  return current;
}

export async function refresh(): Promise<LimiterStatus> {
  const s = await VolumeLimiter.getStatus();
  publish(s);
  return s;
}

/** Opdaterer status fra et svar fra pluginet (de fleste kald returnerer den nye status). */
export function accept(s: LimiterStatus): LimiterStatus {
  publish(s);
  return s;
}

export function subscribe(fn: Listener): () => void {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

const resumeListeners = new Set<() => void>();

/** Kaldes, når appen vender tilbage til forgrunden (fx fra Androids indstillinger). */
export function onResume(fn: () => void): () => void {
  resumeListeners.add(fn);
  return () => resumeListeners.delete(fn);
}

export async function startStatusUpdates() {
  await VolumeLimiter.addListener('statusChanged', publish);
  await VolumeLimiter.addListener('appResumed', () => resumeListeners.forEach((fn) => fn()));
  // Fanger fx at appen kommer tilbage fra baggrunden i browser-simuleringen.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') void refresh();
  });
  await refresh();
}
