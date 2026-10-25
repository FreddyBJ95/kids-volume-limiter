import { VolumeLimiter, errorMessage, type LimitRoute, type LimiterStatus } from '../plugin';
import { accept, refresh, status, subscribe } from '../state';
import { clear, el, icon } from './dom';
import { ROUTES, countdown, percent, protectionState, stepsFor } from './format';
import { requirePin } from './pinPad';
import { toast } from './toast';
import type { View, Navigate } from './view';

export function homeView(navigate: Navigate): View {
  let dragging = false;
  let unlockDeadline = 0;
  /** Den udgang, slideren lige nu styrer (låses fast, mens der trækkes). */
  let sliderRoute: LimitRoute = 'speaker';

  // Statuskort
  const statusIcon = el('div', { class: 'status-icon' });
  const statusTitle = el('h2', { class: 'status-title' });
  const statusText = el('p', { class: 'status-text' });
  const statusAction = el('div', { class: 'status-action' });
  const statusCard = el('section', { class: 'card status-card' }, statusIcon, el('div', { class: 'status-body' }, statusTitle, statusText), statusAction);

  // Aktuel lydstyrke
  const currentValue = el('div', { class: 'big-value' });
  const meter = el('div', { class: 'meter', role: 'img' });
  const routeLine = el('p', { class: 'muted route-line' });
  const currentCard = el(
    'section',
    { class: 'card' },
    el('h3', { class: 'card-label' }, 'Aktuel lydstyrke'),
    currentValue,
    meter,
    routeLine,
  );

  // Maksimal lydstyrke for den aktuelle udgang
  const limitLabel = el('h3', { class: 'card-label' });
  const limitValue = el('div', { class: 'big-value accent' });
  const slider = el('input', {
    type: 'range',
    min: '10',
    max: '100',
    step: '5',
    class: 'slider',
    'aria-label': 'Maksimal lydstyrke',
  });
  const limitHint = el('p', { class: 'muted' });
  const sliderWrap = el('div', { class: 'slider-wrap' }, slider);
  const sliderScale = el('div', { class: 'slider-scale muted' }, el('span', {}, '10 %'), el('span', {}, '100 %'));
  const limitedBox = el('div', {}, limitValue, sliderWrap, sliderScale);
  const unlimitedText = el('p', { class: 'unlimited-text' });
  const enableRouteButton = el('button', { class: 'btn btn-primary', type: 'button', onClick: () => void enableRouteLimit() });
  const unlimitedBox = el('div', { class: 'unlimited' }, unlimitedText, enableRouteButton);
  const limitCard = el('section', { class: 'card limit-card' }, limitLabel, limitedBox, unlimitedBox, limitHint);

  // Lås-linje
  const lockText = el('span', { class: 'lock-text' });
  const lockIcon = el('span', { class: 'lock-icon' });
  const lockButton = el('button', { class: 'btn btn-secondary', type: 'button' });
  const lockBar = el('section', { class: 'lock-bar' }, lockIcon, lockText, lockButton);

  const settingsButton = el(
    'button',
    { class: 'icon-btn', type: 'button', 'aria-label': 'Indstillinger', onClick: () => void openSettings() },
    icon('gear'),
    el('span', { class: 'icon-btn-label' }, 'Indstillinger'),
  );

  const root = el(
    'main',
    { class: 'page home' },
    el('header', { class: 'top-bar' }, el('div', { class: 'brand' }, icon('shield', 'brand-icon'), el('h1', {}, 'Kids Volume Limiter')), settingsButton),
    statusCard,
    el('div', { class: 'grid-2' }, currentCard, limitCard),
    lockBar,
  );

  async function openSettings() {
    if (await requirePin('Indtast PIN-koden for at åbne indstillingerne.')) navigate('settings');
  }

  async function enableRouteLimit() {
    const route = status().limitRoute;
    if (route === 'speaker') return;
    if (!(await requirePin(`Indtast PIN-koden for at sætte en grænse for ${ROUTES[route].label.toLowerCase()}.`))) return;
    try {
      accept(await VolumeLimiter.setRouteLimitEnabled({ route, enabled: true }));
      toast(`${ROUTES[route].label} begrænses nu. Vælg niveauet med slideren.`);
    } catch (err) {
      toast(errorMessage(err), 'error');
    }
  }

  sliderWrap.addEventListener('click', async () => {
    if (!slider.disabled) return;
    await requirePin('Indtast PIN-koden for at ændre den maksimale lydstyrke.');
  });
  slider.addEventListener('pointerdown', () => (dragging = true));
  slider.addEventListener('input', () => {
    const p = Number(slider.value);
    limitValue.textContent = percent(p);
    limitHint.textContent = stepHint(p, status());
  });
  slider.addEventListener('change', async () => {
    dragging = false;
    const route = sliderRoute;
    try {
      accept(await VolumeLimiter.setLimit({ route, percent: Number(slider.value) }));
      toast(`Grænsen for ${ROUTES[route].label.toLowerCase()} er sat til ${slider.value} %`);
    } catch (err) {
      toast(errorMessage(err), 'error');
      render(await refresh());
    }
  });

  lockButton.addEventListener('click', async () => {
    if (status().unlocked) accept(await VolumeLimiter.lock());
    else await requirePin('Indtast PIN-koden for at låse op.');
  });

  function render(s: LimiterStatus) {
    renderStatus(s);

    currentValue.textContent = percent(s.currentPercent);
    renderMeter(s);
    const route = ROUTES[s.route];
    clear(routeLine);
    routeLine.append(icon(route.icon, 'icon icon-sm'), ` ${route.label} · trin ${s.currentIndex} af ${s.maxIndex}`);

    renderLimitCard(s);

    clear(lockIcon);
    lockIcon.append(icon(s.unlocked ? 'unlock' : 'lock'));
    lockBar.classList.toggle('unlocked', s.unlocked);
    lockButton.textContent = s.unlocked ? 'Lås nu' : 'Lås op med PIN';
    unlockDeadline = s.unlocked ? Date.now() + s.unlockSecondsLeft * 1000 : 0;
    renderLockText();
  }

  function renderLimitCard(s: LimiterStatus) {
    if (!dragging) sliderRoute = s.limitRoute;
    const route = sliderRoute;
    const limit = s.limits[route];
    const label = ROUTES[route].label;
    limitLabel.textContent = route === 'speaker' ? 'Maksimal tilladt lydstyrke' : `Maks. lydstyrke · ${label}`;
    limitCard.dataset.route = route;

    limitedBox.hidden = !limit.enabled;
    unlimitedBox.hidden = limit.enabled;
    if (!limit.enabled) {
      unlimitedText.textContent = `${label} er tilsluttet, men har ingen grænse. Lydstyrken her bliver ikke ændret.`;
      enableRouteButton.textContent = `Sæt grænse for ${label.toLowerCase()}`;
      limitHint.textContent = 'Kræver PIN-kode. Højttalerne er stadig beskyttet.';
      return;
    }
    if (!dragging) {
      slider.value = String(limit.percent);
      limitValue.textContent = percent(limit.percent);
      limitHint.textContent = s.unlocked ? stepHint(limit.percent, s) : 'Lås op med PIN-koden for at ændre grænsen.';
    }
    slider.disabled = !s.unlocked;
    sliderWrap.classList.toggle('locked', !s.unlocked);
  }

  function secondsLeft() {
    return Math.max(0, Math.ceil((unlockDeadline - Date.now()) / 1000));
  }

  function renderLockText() {
    lockText.textContent = unlockDeadline
      ? `Låst op · låses igen om ${countdown(secondsLeft())}`
      : 'Indstillingerne er låst med PIN-kode';
  }

  function renderStatus(s: LimiterStatus) {
    const state = protectionState(s);
    statusCard.dataset.state = state;
    clear(statusIcon);
    clear(statusAction);
    const limitText = `${s.effectiveLimitPercent} %`;
    const routeLabel = ROUTES[s.route].label;
    switch (state) {
      case 'active':
        statusIcon.append(icon(s.route === 'speaker' ? 'shieldCheck' : ROUTES[s.route].icon));
        statusTitle.textContent = 'Beskyttelse aktiv';
        statusText.textContent =
          s.route === 'speaker'
            ? `De indbyggede højttalere holdes på højst ${limitText}.`
            : `${routeLabel} holdes på højst ${limitText}. Højttalerne har deres egen grænse.`;
        break;
      case 'paused':
        statusIcon.append(icon(ROUTES[s.route].icon));
        statusTitle.textContent = 'Beskyttelse aktiv';
        statusText.textContent = s.volumeFixed
          ? 'Den aktuelle lydudgang har fast lydstyrke, som Android ikke lader apps ændre.'
          : `Lyden går lige nu til ${routeLabel.toLowerCase()}, som ikke begrænses. Højttalerne er stadig beskyttet.`;
        break;
      case 'stopped':
        statusIcon.append(icon('alert'));
        statusTitle.textContent = 'Beskyttelse inaktiv';
        statusText.textContent = 'Android har stoppet overvågningen. Start den igen, og tjek batteriindstillingerne.';
        statusAction.append(actionButton('Start igen'));
        break;
      case 'off':
        statusIcon.append(icon('shieldOff'));
        statusTitle.textContent = 'Beskyttelse inaktiv';
        statusText.textContent = 'Lydstyrken bliver ikke begrænset lige nu.';
        statusAction.append(actionButton('Slå til'));
        break;
    }
  }

  function actionButton(label: string) {
    return el(
      'button',
      {
        class: 'btn btn-primary',
        type: 'button',
        onClick: async () => {
          try {
            accept(await VolumeLimiter.setEnabled({ enabled: true }));
          } catch (err) {
            toast(errorMessage(err), 'error');
          }
        },
      },
      label,
    );
  }

  function renderMeter(s: LimiterStatus) {
    const segments = Math.max(1, Math.min(s.maxIndex, 30));
    const scale = s.maxIndex / segments;
    const filled = Math.round(s.currentIndex / scale);
    const limitSeg = s.routeLimited && s.enabled ? Math.round(s.limitIndex / scale) : segments;
    clear(meter);
    meter.setAttribute('aria-label', `Lydstyrke ${s.currentPercent} procent`);
    for (let i = 1; i <= segments; i++) {
      const cls = ['seg'];
      if (i <= filled) cls.push('on');
      if (i > limitSeg) cls.push('blocked');
      meter.appendChild(el('span', { class: cls.join(' ') }));
    }
  }

  const unlockTimer = window.setInterval(() => {
    if (!unlockDeadline) return;
    if (secondsLeft() > 0) renderLockText();
    else {
      unlockDeadline = 0;
      void refresh();
    }
  }, 1000);

  const unsubscribe = subscribe(render);
  render(status());

  return {
    root,
    destroy() {
      unsubscribe();
      window.clearInterval(unlockTimer);
    },
  };
}

function stepHint(p: number, s: LimiterStatus) {
  const steps = stepsFor(p, s);
  return `Svarer til trin ${steps} af ${s.maxIndex} på denne tablet.`;
}
