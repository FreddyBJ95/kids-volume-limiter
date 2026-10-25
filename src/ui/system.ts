import { VolumeLimiter, errorMessage, type DeviceState, type SelfTestResult } from '../plugin';
import { refresh } from '../state';
import { clear, el, icon, type IconName } from './dom';
import { toast } from './toast';

/** Tilladelser og batteriindstillinger, som baggrundsdriften afhænger af. */
export function permissionsPanel() {
  const rows = el('div', { class: 'perm-list' });
  const tips = el('div');
  const root = el('div', { class: 'perm-panel' }, rows, tips);

  async function update() {
    let d: DeviceState;
    try {
      d = await VolumeLimiter.getDeviceState();
    } catch (err) {
      toast(errorMessage(err), 'error');
      return;
    }
    clear(rows);
    rows.append(
      permRow(
        'bell',
        'Notifikationer',
        'Android kræver en fast notifikation, mens appen holder øje med lydstyrken i baggrunden.',
        d.notificationsGranted,
        d.notificationsGranted ? 'Tilladt' : 'Tillad',
        async () => {
          const r = await VolumeLimiter.requestNotificationPermission();
          if (!r.granted) {
            toast('Notifikationer er ikke tilladt. Du kan slå dem til under app-indstillinger.');
          }
        },
      ),
      permRow(
        'battery',
        'Ubegrænset batteriforbrug',
        'Undtag appen fra batterioptimering, så Android ikke stopper overvågningen efter længere tids inaktivitet.',
        d.ignoringBatteryOptimizations,
        d.ignoringBatteryOptimizations ? 'Undtaget' : 'Tillad',
        async () => {
          await VolumeLimiter.requestIgnoreBatteryOptimizations();
        },
      ),
    );
    if (d.backgroundRestricted) {
      rows.append(
        el(
          'div',
          { class: 'notice notice-warn' },
          icon('alert'),
          el('p', {}, 'Baggrundsbrug er begrænset for appen. Åbn app-indstillinger › Batteri, og vælg "Ubegrænset".'),
        ),
      );
    }
    clear(tips);
    tips.append(manufacturerTips(d.manufacturer));
  }

  function permRow(iconName: IconName, title: string, text: string, ok: boolean, label: string, action: () => Promise<void>) {
    const button = el(
      'button',
      {
        class: `btn ${ok ? 'btn-done' : 'btn-primary'}`,
        type: 'button',
        disabled: ok,
        onClick: async () => {
          try {
            await action();
          } catch (err) {
            toast(errorMessage(err), 'error');
          }
          await update();
          await refresh();
        },
      },
      ok ? icon('check', 'icon icon-sm') : null,
      label,
    );
    return el(
      'div',
      { class: `perm-row${ok ? ' ok' : ''}` },
      icon(iconName, 'icon perm-icon'),
      el('div', { class: 'perm-body' }, el('strong', {}, title), el('p', { class: 'muted' }, text)),
      button,
    );
  }

  return { root, update };
}

/** Konkrete råd til de producenter, appen skal bruges på. Appen er ikke afhængig af dem. */
export function manufacturerTips(manufacturer: string): HTMLElement {
  const m = manufacturer.toLowerCase();
  let title = 'Tjek batteriindstillingerne';
  let steps: string[] = [
    'Åbn Indstillinger › Apps › Kids Volume Limiter › Batteri, og vælg "Ubegrænset".',
    'Lad appen stå i baggrunden. Luk den ikke fra listen over seneste apps med "Luk alle", hvis tabletten tilbyder at tvangsstoppe apps.',
  ];
  if (m.includes('samsung')) {
    title = 'Samsung Galaxy Tab';
    steps = [
      'Indstillinger › Apps › Kids Volume Limiter › Batteri: vælg "Ubegrænset".',
      'Indstillinger › Batteri › Grænser for baggrundsbrug: sørg for, at appen IKKE står under "Apps i dvale" eller "Apps i dyb dvale". Tilføj den gerne til "Apps, der aldrig sover".',
      'Slå evt. "Sæt ubrugte apps i dvale" fra samme sted.',
    ];
  } else if (m.includes('lenovo') || m.includes('motorola')) {
    title = 'Lenovo-tablet';
    steps = [
      'Indstillinger › Apps › Kids Volume Limiter › Batteri (eller "Appens batteriforbrug"): vælg "Ubegrænset".',
      'Har tabletten ZUI eller en "Baggrundsstyring"/"Autostart"-menu, så tillad, at appen kører i baggrunden og starter automatisk.',
      'Tjek også Indstillinger › Batteri › Batterisparetilstand: Den kan begrænse baggrundsapps, mens den er slået til.',
    ];
  }
  return el(
    'div',
    { class: 'notice notice-info tips' },
    icon('info'),
    el('div', {}, el('strong', {}, title), el('ol', {}, ...steps.map((s) => el('li', {}, s)))),
    el('button', { class: 'btn btn-secondary', type: 'button', onClick: () => void VolumeLimiter.openAppSettings() }, 'Åbn app-indstillinger'),
  );
}

/** Kører selvtesten og viser resultatet. */
export function selfTestPanel() {
  const results = el('div', { class: 'selftest-results' });
  const button = el('button', { class: 'btn btn-primary', type: 'button', onClick: () => void run() }, icon('play', 'icon icon-sm'), 'Kør selvtest');
  const root = el('div', { class: 'selftest' }, results, button);

  async function run(): Promise<SelfTestResult | null> {
    button.disabled = true;
    clear(results);
    results.append(el('p', { class: 'muted' }, 'Tester … Lydstyrken ændres kortvarigt et enkelt trin.'));
    try {
      const r = await VolumeLimiter.runSelfTest();
      render(r);
      return r;
    } catch (err) {
      clear(results);
      results.append(el('p', { class: 'pin-error' }, errorMessage(err)));
      return null;
    } finally {
      button.disabled = false;
      void refresh();
    }
  }

  function render(r: SelfTestResult) {
    clear(results);
    const list = el('ul', { class: 'check-list' });
    for (const step of r.steps) {
      const iconName: IconName = step.status === 'ok' ? 'check' : step.status === 'fail' ? 'x' : 'minus';
      list.append(
        el(
          'li',
          { class: `check ${step.status}` },
          icon(iconName, 'icon check-icon'),
          el('div', {}, el('strong', {}, step.label), el('p', { class: 'muted' }, step.detail)),
        ),
      );
    }
    results.append(
      el(
        'div',
        { class: `notice ${r.ok ? 'notice-ok' : 'notice-warn'}` },
        icon(r.ok ? 'shieldCheck' : 'alert'),
        el(
          'p',
          {},
          r.ok
            ? `Alt virker.${r.latencyMs !== null ? ` Målt forsinkelse fra overskridelse til sænkning: ${r.latencyMs} ms.` : ''}`
            : 'Noget virker ikke endnu. Se detaljerne herunder.',
        ),
      ),
      list,
    );
  }

  return { root, run };
}
