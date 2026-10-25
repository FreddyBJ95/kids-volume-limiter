import { registerPlugin } from '@capacitor/core';
import type { LimiterErrorCode, VolumeLimiterPlugin } from './definitions';

export * from './definitions';

/**
 * På Android bruges det native Kotlin-plugin. I en almindelig browser (npm run dev) bruges en
 * simulering, så brugerfladen kan udvikles og afprøves uden tablet.
 */
export const VolumeLimiter = registerPlugin<VolumeLimiterPlugin>('VolumeLimiter', {
  web: () => import('./web').then((m) => new m.VolumeLimiterWeb()),
});

export function errorCode(err: unknown): LimiterErrorCode {
  const code = (err as { code?: string } | null)?.code;
  return (code as LimiterErrorCode) ?? 'ERROR';
}

export function errorMessage(err: unknown): string {
  return (err as { message?: string } | null)?.message ?? 'Der opstod en ukendt fejl.';
}
