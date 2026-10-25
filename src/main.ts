import './styles.css';
import { errorMessage } from './plugin';
import { startStatusUpdates, status } from './state';
import { el } from './ui/dom';
import { homeView } from './ui/home';
import { onboardingView } from './ui/onboarding';
import { settingsView } from './ui/settings';
import type { Route, View } from './ui/view';

const app = document.getElementById('app')!;
let current: View | null = null;
let currentRoute: Route | 'onboarding' | null = null;

function show(view: View, route: Route | 'onboarding') {
  current?.destroy();
  current = view;
  currentRoute = route;
  app.replaceChildren(view.root);
  window.scrollTo(0, 0);
}

function navigate(to: Route) {
  if (to === currentRoute) return;
  show(to === 'settings' ? settingsView(navigate) : homeView(navigate), to);
}

async function boot() {
  try {
    await startStatusUpdates();
  } catch (err) {
    app.replaceChildren(el('main', { class: 'page' }, el('section', { class: 'card' }, el('h1', {}, 'Kids Volume Limiter'), el('p', { class: 'pin-error' }, errorMessage(err)))));
    return;
  }
  if (status().onboardingDone) navigate('home');
  else show(onboardingView(() => navigate('home')), 'onboarding');
}

void boot();
