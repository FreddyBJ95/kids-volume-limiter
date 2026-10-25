import { VolumeLimiter, errorCode, errorMessage } from '../plugin';
import { accept, onResume, refresh, status, subscribe } from '../state';
import { clear, el, icon } from './dom';
import { percent, stepsFor } from './format';
import { PinPad, newPinPad } from './pinPad';
import { permissionsPanel, selfTestPanel } from './system';
import { toast } from './toast';
import type { View } from './view';

const STEPS = ['Velkommen', 'PIN-kode', 'Grænse', 'Tilladelser', 'Aktivér', 'Test'] as const;

/** Første opstart: formål → PIN → grænse → tilladelser → aktivering → kontrol. */
export function onboardingView(onDone: () => void): View {
  let step = 0;
  let cleanup: (() => void) | null = null;

  const progress = el('ol', { class: 'stepper' });
  const body = el('div', { class: 'onboarding-body' });
  const root = el(
    'main',
    { class: 'page onboarding' },
    el('header', { class: 'top-bar' }, el('div', { class: 'brand' }, icon('shield', 'brand-icon'), el('h1', {}, 'Kids Volume Limiter'))),
    progress,
    el('section', { class: 'card onboarding-card' }, body),
  );

  function go(next: number) {
    cleanup?.();
    cleanup = null;
    step = next;
    clear(progress);
    STEPS.forEach((label, i) =>
      progress.append(el('li', { class: i < step ? 'done' : i === step ? 'current' : '' }, el('span', { class: 'step-dot' }, String(i + 1)), el('span', { class: 'step-label' }, label))),
    );
    clear(body);
    [welcome, pin, limit, permissions, activate, test][step]();
    body.scrollIntoView({ block: 'start' });
  }

  function actions(...buttons: HTMLElement[]) {
    return el('div', { class: 'actions' }, ...buttons);
  }

  function button(label: string, onClick: () => void, kind = 'btn-primary', disabled = false) {
    return el('button', { class: `btn ${kind}`, type: 'button', disabled, onClick }, label);
  }

  // 1. Formål
  function welcome() {
    body.append(
      el('h2', {}, 'Velkommen'),
      el(
        'p',
        { class: 'lead' },
        'Appen holder øje med tablettens medielydstyrke og skruer automatisk ned, hvis barnet skruer højere op end den grænse, du vælger.',
      ),
      el('p', {}, 'Det gælder lyd fra de indbyggede højttalere i alle apps: YouTube, Netflix, spil og andre.'),
      el(
        'div',
        { class: 'notice notice-info' },
        icon('info'),
        el(
          'p',
          {},
          'Godt at vide: Android tillader ikke almindelige apps at låse lydstyrken helt. Appen reagerer i stedet på hver ændring og sænker lyden igen med det samme. ' +
            'PIN-koden beskytter appens indstillinger, men kan ikke forhindre, at appen afinstalleres eller tvangsstoppes.',
        ),
      ),
      actions(button('Kom i gang', () => go(1))),
    );
  }

  // 2. PIN-kode
  function pin() {
    const s = status();
    let pad: PinPad;
    if (s.pinSet && !s.unlocked) {
      // Opsætningen blev afbrudt efter, at PIN-koden var oprettet.
      pad = new PinPad({
        title: 'Indtast din PIN-kode',
        subtitle: 'Du har allerede oprettet en PIN-kode. Indtast den for at fortsætte opsætningen.',
        onSubmit: async (value) => {
          const r = await VolumeLimiter.verifyPin({ pin: value });
          if (r.ok) {
            await refresh();
            go(2);
            return null;
          }
          if (r.lockoutSeconds > 0) pad.lockFor(r.lockoutSeconds);
          return r.lockoutSeconds > 0 ? `For mange forkerte forsøg. Prøv igen om ${r.lockoutSeconds} sek.` : 'Forkert PIN-kode.';
        },
      });
    } else if (s.pinSet) {
      go(2);
      return;
    } else {
      pad = newPinPad(async (value) => {
        try {
          accept(await VolumeLimiter.createPin({ pin: value }));
          toast('PIN-koden er oprettet');
          go(2);
          return null;
        } catch (err) {
          if (errorCode(err) === 'PIN_EXISTS') {
            await refresh();
            go(1);
            return null;
          }
          return errorMessage(err);
        }
      });
    }
    body.append(
      el('p', { class: 'muted center' }, 'PIN-koden skal bruges, når grænsen ændres, beskyttelsen slås fra, eller indstillingerne ændres.'),
      pad.root,
      actions(button('Tilbage', () => go(0), 'btn-ghost')),
    );
    cleanup = () => pad.destroy();
  }

  // 3. Grænse
  function limit() {
    const s = status();
    const initial = s.limits.speaker.percent;
    const value = el('div', { class: 'big-value accent center' }, percent(initial));
    const hint = el('p', { class: 'muted center' });
    const slider = el('input', { type: 'range', min: '10', max: '100', step: '5', value: String(initial), class: 'slider', 'aria-label': 'Maksimal lydstyrke' });
    const update = () => {
      const p = Number(slider.value);
      value.textContent = percent(p);
      hint.textContent = `Svarer til trin ${stepsFor(p, status())} af ${status().maxIndex} på denne tablet. Anbefalet til børn: 30–50 %.`;
    };
    slider.addEventListener('input', update);
    update();
    body.append(
      el('h2', {}, 'Vælg maksimal lydstyrke'),
      el('p', {}, 'Lydstyrken i de indbyggede højttalere kan aldrig komme over denne grænse. Du kan altid ændre den senere med din PIN-kode.'),
      value,
      el('div', { class: 'slider-wrap' }, slider),
      el('div', { class: 'slider-scale muted' }, el('span', {}, '10 %'), el('span', {}, '100 %')),
      hint,
      el(
        'div',
        { class: 'notice notice-info' },
        icon('headphones'),
        el('p', {}, 'Hovedtelefoner og Bluetooth kan få deres egen grænse. Når de er tilsluttet, kan du vælge niveauet på forsiden eller under Indstillinger.'),
      ),
      actions(
        button('Tilbage', () => go(0), 'btn-ghost'),
        button('Gem og fortsæt', async () => {
          try {
            accept(await VolumeLimiter.setLimit({ route: 'speaker', percent: Number(slider.value) }));
            go(3);
          } catch (err) {
            if (errorCode(err) === 'LOCKED') go(1);
            else toast(errorMessage(err), 'error');
          }
        }),
      ),
    );
  }

  // 4. Tilladelser og batteri
  function permissions() {
    const panel = permissionsPanel();
    void panel.update();
    cleanup = onResume(() => void panel.update());
    body.append(
      el('h2', {}, 'Tilladelser og batteri'),
      el(
        'p',
        {},
        'For at begrænsningen også virker, når appen er lukket, kører den som en lille baggrundstjeneste med en fast notifikation. Den bruger ikke timere og vækker kun tabletten, når lydstyrken ændres.',
      ),
      panel.root,
      actions(button('Tilbage', () => go(2), 'btn-ghost'), button('Fortsæt', () => go(4))),
    );
  }

  // 5. Aktivér overvågning
  function activate() {
    const result = el('div');
    const activateButton = button('Aktivér beskyttelse', async () => {
      activateButton.disabled = true;
      try {
        accept(await VolumeLimiter.setEnabled({ enabled: true }));
      } catch (err) {
        toast(errorMessage(err), 'error');
      }
      activateButton.disabled = false;
    });
    const next = button('Fortsæt til test', () => go(5));
    const render = () => {
      const s = status();
      clear(result);
      if (s.enabled && s.serviceRunning) {
        result.append(el('div', { class: 'notice notice-ok' }, icon('shieldCheck'), el('p', {}, 'Beskyttelsen er aktiv, og overvågningen kører i baggrunden.')));
      } else if (s.enabled) {
        result.append(el('div', { class: 'notice notice-warn' }, icon('alert'), el('p', {}, 'Beskyttelsen er slået til, men Android har ikke startet overvågningen endnu. Prøv igen.')));
      }
      activateButton.hidden = s.enabled && s.serviceRunning;
      next.disabled = !s.enabled;
    };
    cleanup = subscribe(render);
    body.append(
      el('h2', {}, 'Aktivér beskyttelse'),
      el('p', {}, 'Når beskyttelsen er aktiv, kontrolleres lydstyrken med det samme og derefter hver gang, den ændres – også efter genstart af tabletten.'),
      result,
      actions(button('Tilbage', () => go(3), 'btn-ghost'), activateButton, next),
    );
    render();
  }

  // 6. Kontrollér, at lydstyrken kan reguleres
  function test() {
    const panel = selfTestPanel();
    const s = status();
    body.append(
      el('h2', {}, 'Kontrol'),
      el('p', {}, 'Appen tester nu, at den kan regulere lydstyrken på denne tablet.'),
      panel.root,
      el(
        'div',
        { class: 'notice notice-info' },
        icon('speaker'),
        el('p', {}, `Prøv også selv: Start en video, og skru op med tablettens lydstyrkeknapper. Lyden fra højttalerne bør stoppe ved ${s.limits.speaker.effectivePercent} %.`),
      ),
      actions(
        button('Tilbage', () => go(4), 'btn-ghost'),
        button('Færdig', async () => {
          try {
            accept(await VolumeLimiter.completeOnboarding());
            onDone();
          } catch (err) {
            toast(errorMessage(err), 'error');
          }
        }),
      ),
    );
    void panel.run();
  }

  go(0);
  return {
    root,
    destroy() {
      cleanup?.();
    },
  };
}
