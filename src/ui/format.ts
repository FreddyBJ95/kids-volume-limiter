import type { LimiterStatus, OutputRoute } from '../plugin';
import type { IconName } from './dom';

export const ROUTES: Record<OutputRoute, { label: string; icon: IconName }> = {
  speaker: { label: 'Indbyggede højttalere', icon: 'speaker' },
  headphones: { label: 'Hovedtelefoner', icon: 'headphones' },
  bluetooth: { label: 'Bluetooth', icon: 'bluetooth' },
  other: { label: 'Anden lydudgang', icon: 'output' },
};

export function percent(n: number): string {
  return `${n} %`;
}

export function dateTime(ms: number): string {
  if (!ms) return '–';
  return new Date(ms).toLocaleString('da-DK', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

export function countdown(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}:${String(s).padStart(2, '0')}`;
}

export function stepsFor(limitPercent: number, s: Pick<LimiterStatus, 'maxIndex' | 'minIndex'>): number {
  if (s.maxIndex <= 0) return 0;
  return Math.min(s.maxIndex, Math.max(Math.floor((s.maxIndex * limitPercent) / 100), s.minIndex, 1));
}

export type ProtectionState = 'active' | 'paused' | 'stopped' | 'off';

/** Samlet status til forsiden: aktiv, aktiv men ikke på denne udgang, stoppet af Android eller slået fra. */
export function protectionState(s: LimiterStatus): ProtectionState {
  if (!s.enabled) return 'off';
  if (!s.serviceRunning) return 'stopped';
  if (!s.routeLimited || s.volumeFixed) return 'paused';
  return 'active';
}
