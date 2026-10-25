import { VolumeLimiter, errorMessage, type LimitRoute, type LimiterStatus } from '../plugin';
import { accept, onResume, refresh, status, subscribe } from '../state';
import { clear, el, icon } from './dom';
import { ROUTES, dateTime, percent, stepsFor } from './format';
import { changePinDialog } from './pinPad';
import { permissionsPanel, selfTestPanel } from './system';
import { toast } from './toast';
import type { Navigate, View } from './view';

export function settingsView(navigate: Navigate): View {
  const enabledSwitch = switchInput('Lydstyrkebegrænsning');
  enabledSwitch.input.addEventListener('change', () =>
    save(() => VolumeLimiter.setEnabled({ enabled: enabledSwitch.input.checked }), enabledSwitch.input.checked ? 'Beskyttelsen er slået til' : 'Beskyttelsen er slået fra'),
  );
  const routeControls = (['speaker', 'headphones', 'bluetooth'] as const).map((route) => routeLimitControl(route, save));

  const permissions = permissionsPanel();
  const selfTest = selfTestPanel();
  const diagnostics = el('div', { class: 'diag' });

  const root = el(
    'main',
    { class: 'page settings' },
    el(
      'header',
      { class: 'top-bar' },
      el('button', { class: 'icon-btn', type: 'button', 'aria-label': 'Tilbage', onClick: () => navigate('home') }, icon('back'), el('span', { class: 'icon-btn-label' }, 'Tilbage')),
      el('h1', {}, 'Indstillinger'),
      el('span', { class: 'top-bar-spacer' }),
    ),
    section('Beskyttelse', settingRow(enabledSwitch.root, 'Sænker automatisk medielydstyrken, hvis den kommer over grænsen.')),
    section(
      'Grænser pr. lydudgang',
      el(
        'p',
        { class: 'muted' },
        'Android husker en separat lydstyrke for hver lydudgang. Højttalerne begrænses altid. Hovedtelefoner og Bluetooth kan få deres egen grænse – ellers bliver deres lydstyrke ikke ændret.',
      ),
      ...routeControls.map((c) => c.root),
    ),
    section(
      'PIN-kode',
      el(
        'div',
        { class: 'setting-row' },
        el('div', { class: 'setting-text' }, el('strong', {}, 'Forældre-PIN'), el('p', { class: 'muted' }, 'Kræves for at ændre grænsen, slå beskyttelsen fra og ændre indstillinger.')),
        el(
          'button',
          {
            class: 'btn btn-secondary',
            type: 'button',
            onClick: async () => {
              if (await changePinDialog()) toast('PIN-koden er ændret');
            },
          },
          icon('key', 'icon icon-sm'),
          'Skift PIN-kode',
        ),
      ),
    ),
    section('Baggrundsdrift', permissions.root),
    section('Test', el('p', { class: 'muted' }, 'Kontrollerer, at overvågningen kører, og at appen kan sænke lydstyrken på denne tablet. Testen måler også forsinkelsen.'), selfTest.root),
    section('Diagnostik', diagnostics),
    section(
      'Om appen',
      el(
        'div',
        { class: 'about' },
        el('p', {}, 'Kids Volume Limiter 1.0.0 – virker uden internet, konto eller abonnement.'),
        el(
          'p',
          { class: 'muted' },
          'Android lader ikke almindelige apps låse systemets lydstyrke. Appen reagerer i stedet på hver ændring og sænker lyden igen med det samme. ' +
            'PIN-koden beskytter appens indstillinger, men kan ikke forhindre, at appen afinstalleres, tvangsstoppes, eller at Androids egne indstillinger ændres.',
        ),
      ),
    ),
  );

  async function save(action: () => Promise<LimiterStatus>, message: string) {
    try {
      accept(await action());
      toast(message);
    } catch (err) {
      toast(errorMessage(err), 'error');
      render(status());
    }
    void renderDiagnostics();
  }

  let relockTimer: number | undefined;

  function render(s: LimiterStatus) {
    window.clearTimeout(relockTimer);
    if (!s.unlocked) {
      toast('Indstillingerne er låst igen');
      navigate('home');
      return;
    }
    enabledSwitch.input.checked = s.enabled;
    routeControls.forEach((c) => c.render(s));
    // Oplåsningen udløber i den native del; hent status igen, når den forventes at være udløbet.
    relockTimer = window.setTimeout(() => void refresh(), s.unlockSecondsLeft * 1000 + 500);
  }

  async function renderDiagnostics() {
    const [d, dev] = await Promise.all([VolumeLimiter.getDiagnostics(), VolumeLimiter.getDeviceState()]);
    const s = status();
    const last = d.recent[0];
    clear(diagnostics);
    diagnostics.append(
      kv('Automatiske sænkninger i alt', String(d.corrections)),
      kv('Seneste sænkning', last ? `${dateTime(last.at)} · trin ${last.from} → ${last.to}` : '–'),
      kv('Seneste udløser og behandlingstid', last ? `${last.trigger} · ${last.handlingMs} ms` : '–'),
      kv('Overvågning', s.serviceRunning ? `Kører (startet ${dateTime(d.serviceStartedAt)}, ${d.serviceStartReason})` : 'Kører ikke'),
      kv('Seneste genstart af tablet', d.lastBootAt ? `${dateTime(d.lastBootAt)} · ${d.lastBootStartOk ? 'overvågning startet' : 'Android tillod ikke start'}` : '–'),
      kv('Vagthund senest kørt', dateTime(d.lastWatchdogAt)),
      kv('Lydudgang', ROUTES[s.route].label),
      kv('Lydstyrketrin på enheden', `${s.minIndex}–${s.maxIndex} (grænse: trin ${s.limitIndex})`),
      kv('Enhed', `${dev.manufacturer} ${dev.model} · Android ${dev.androidVersion} (API ${dev.sdkInt})`),
    );
    if (d.recent.length > 1) {
      diagnostics.append(
        el('details', {}, el('summary', {}, 'Seneste 20 sænkninger'),
          el('ul', { class: 'log' }, ...d.recent.map((e) => el('li', {}, `${dateTime(e.at)} · ${e.from} → ${e.to} · ${ROUTES[e.route]?.label ?? e.route} · ${e.trigger} · ${e.handlingMs} ms`))),
        ),
      );
    }
  }

  const unsubscribe = subscribe(render);
  const offResume = onResume(() => {
    void permissions.update();
    void renderDiagnostics();
  });
  render(status());
  void permissions.update();
  void renderDiagnostics();

  return {
    root,
    destroy() {
      unsubscribe();
      offResume();
      window.clearTimeout(relockTimer);
    },
  };
}

function section(title: string, ...children: (Node | string)[]) {
  return el('section', { class: 'card settings-section' }, el('h2', { class: 'section-title' }, title), ...children);
}

function settingRow(control: HTMLElement, description: string) {
  return el('div', { class: 'setting-row stacked' }, control, el('p', { class: 'muted' }, description));
}

function kv(key: string, value: string) {
  return el('div', { class: 'kv' }, el('span', { class: 'muted' }, key), el('span', {}, value));
}

/** Grænse for én lydudgang: kontakt (ikke for højttalere) + slider. */
function routeLimitControl(route: LimitRoute, save: (action: () => Promise<LimiterStatus>, message: string) => Promise<void>) {
  const label = ROUTES[route].label;
  const toggle = route === 'speaker' ? null : switchInput(`Begræns ${label.toLowerCase()}`);
  const connected = el('span', { class: 'chip' }, 'Bruges nu');
  const value = el('strong', { class: 'limit-value' });
  const hint = el('p', { class: 'muted' });
  const slider = el('input', { type: 'range', min: '10', max: '100', step: '5', class: 'slider', 'aria-label': `Maks. lydstyrke for ${label.toLowerCase()}` });
  const sliderBox = el('div', { class: 'route-slider' }, el('div', { class: 'route-slider-head' }, el('span', { class: 'muted' }, 'Maks. lydstyrke'), value), slider, hint);
  let dragging = false;

  slider.addEventListener('pointerdown', () => (dragging = true));
  slider.addEventListener('input', () => {
    value.textContent = percent(Number(slider.value));
    hint.textContent = stepHint(Number(slider.value), status());
  });
  slider.addEventListener('change', () => {
    dragging = false;
    void save(() => VolumeLimiter.setLimit({ route, percent: Number(slider.value) }), `Grænsen for ${label.toLowerCase()} er sat til ${slider.value} %`);
  });
  toggle?.input.addEventListener('change', () => {
    const enabled = toggle.input.checked;
    void save(
      () => VolumeLimiter.setRouteLimitEnabled({ route: route as Exclude<LimitRoute, 'speaker'>, enabled }),
      enabled ? `${label} begrænses nu` : `${label} begrænses ikke længere`,
    );
  });

  const head = el(
    'div',
    { class: 'route-limit-head' },
    icon(ROUTES[route].icon, 'icon route-icon'),
    toggle ? toggle.root : el('div', { class: 'switch-row' }, el('strong', {}, `${label} (altid begrænset)`)),
  );
  const root = el('div', { class: 'route-limit' }, head, connected, sliderBox);

  function render(s: LimiterStatus) {
    const limit = s.limits[route];
    if (toggle) toggle.input.checked = limit.enabled;
    connected.hidden = s.route !== route;
    sliderBox.hidden = !limit.enabled;
    if (!dragging) {
      slider.value = String(limit.percent);
      value.textContent = percent(limit.percent);
      hint.textContent = stepHint(limit.percent, s);
    }
  }

  return { root, render };
}

function stepHint(p: number, s: LimiterStatus) {
  return `Svarer til trin ${stepsFor(p, s)} af ${s.maxIndex} på denne tablet.`;
}

function switchInput(label: string) {
  const input = el('input', { type: 'checkbox', role: 'switch' });
  const root = el('label', { class: 'switch-row' }, el('strong', {}, label), el('span', { class: 'switch' }, input, el('span', { class: 'switch-track' })));
  return { root, input };
}
