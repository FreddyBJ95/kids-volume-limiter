import { VolumeLimiter, errorMessage } from '../plugin';
import { accept, refresh, status } from '../state';
import { clear, el, icon } from './dom';

const MIN = 4;
const MAX = 6;

function lockoutText(seconds: number) {
  return `For mange forkerte forsøg. Prøv igen om ${seconds} sek.`;
}

interface PinPadOptions {
  title: string;
  subtitle?: string;
  submitLabel?: string;
  /** Returnér en fejltekst for at blive på tastaturet, eller null når koden er accepteret. */
  onSubmit: (pin: string) => Promise<string | null>;
  onCancel?: () => void;
}

/** Numerisk tastatur til PIN-koder (4–6 cifre). */
export class PinPad {
  readonly root: HTMLElement;
  private value = '';
  private busy = false;
  private lockTimer: number | undefined;
  private readonly titleEl: HTMLElement;
  private readonly subtitleEl: HTMLElement;
  private readonly dotsEl: HTMLElement;
  private readonly errorEl: HTMLElement;
  private readonly okButton: HTMLButtonElement;
  private readonly keys: HTMLButtonElement[] = [];

  constructor(private readonly options: PinPadOptions) {
    this.titleEl = el('h2', { class: 'pin-title' }, options.title);
    this.subtitleEl = el('p', { class: 'pin-subtitle' }, options.subtitle ?? '');
    this.dotsEl = el('div', { class: 'pin-dots', 'aria-hidden': 'true' });
    this.errorEl = el('p', { class: 'pin-error', role: 'alert' });
    this.okButton = el('button', { class: 'key key-ok', type: 'button', onClick: () => this.submit() }, options.submitLabel ?? 'OK');

    const grid = el('div', { class: 'pin-keys' });
    for (const d of ['1', '2', '3', '4', '5', '6', '7', '8', '9']) grid.appendChild(this.digitKey(d));
    const back = el('button', { class: 'key key-icon', type: 'button', 'aria-label': 'Slet', onClick: () => this.backspace() }, icon('backspace'));
    this.keys.push(back);
    grid.append(back, this.digitKey('0'), this.okButton);

    const cancel = options.onCancel
      ? el('button', { class: 'btn btn-ghost pin-cancel', type: 'button', onClick: () => options.onCancel?.() }, 'Annullér')
      : null;

    this.root = el('div', { class: 'pin-pad' }, el('div', { class: 'pin-lock' }, icon('lock')), this.titleEl, this.subtitleEl, this.dotsEl, this.errorEl, grid, cancel);
    this.render();
  }

  setText(title: string, subtitle = '') {
    this.titleEl.textContent = title;
    this.subtitleEl.textContent = subtitle;
  }

  reset(error = '') {
    this.value = '';
    this.errorEl.textContent = error;
    this.render();
  }

  /** Spærrer tastaturet i et antal sekunder efter for mange forkerte forsøg. */
  lockFor(seconds: number) {
    window.clearInterval(this.lockTimer);
    this.keys.forEach((k) => (k.disabled = true));
    this.okButton.disabled = true;
    let left = seconds;
    this.lockTimer = window.setInterval(() => {
      left--;
      if (left > 0) {
        this.errorEl.textContent = lockoutText(left);
        return;
      }
      window.clearInterval(this.lockTimer);
      this.keys.forEach((k) => (k.disabled = false));
      this.errorEl.textContent = '';
      this.render();
    }, 1000);
  }

  destroy() {
    window.clearInterval(this.lockTimer);
  }

  private digitKey(d: string) {
    const key = el('button', { class: 'key', type: 'button', onClick: () => this.press(d) }, d);
    this.keys.push(key);
    return key;
  }

  private press(d: string) {
    if (this.busy || this.value.length >= MAX) return;
    this.value += d;
    this.errorEl.textContent = '';
    this.render();
  }

  private backspace() {
    if (this.busy) return;
    this.value = this.value.slice(0, -1);
    this.render();
  }

  private async submit() {
    if (this.busy || this.value.length < MIN) return;
    this.busy = true;
    this.root.classList.add('busy');
    try {
      const error = await this.options.onSubmit(this.value);
      if (error !== null) {
        this.reset(error);
        this.root.classList.remove('shake');
        void this.root.offsetWidth;
        this.root.classList.add('shake');
      }
    } finally {
      this.busy = false;
      this.root.classList.remove('busy');
      this.render();
    }
  }

  private render() {
    clear(this.dotsEl);
    for (let i = 0; i < MAX; i++) {
      this.dotsEl.appendChild(el('span', { class: `dot${i < this.value.length ? ' filled' : ''}${i >= MIN ? ' optional' : ''}` }));
    }
    this.okButton.disabled = this.value.length < MIN || this.busy || this.keys[0]?.disabled === true;
  }
}

/** Åbner en modal dialog og returnerer en funktion, der lukker den. */
export function openModal(content: HTMLElement, onBackdrop?: () => void): () => void {
  const overlay = el('div', { class: 'modal-backdrop' }, el('div', { class: 'modal', role: 'dialog', 'aria-modal': 'true' }, content));
  overlay.addEventListener('click', (e) => {
    if (e.target === overlay) onBackdrop?.();
  });
  document.body.appendChild(overlay);
  requestAnimationFrame(() => overlay.classList.add('open'));
  return () => {
    overlay.classList.remove('open');
    setTimeout(() => overlay.remove(), 180);
  };
}

/**
 * Sikrer, at indstillingerne er låst op. Viser PIN-tastaturet, hvis det er nødvendigt.
 * Returnerer true, når forælderen har indtastet den korrekte PIN-kode.
 */
export async function requirePin(reason = 'Indtast PIN-koden for at fortsætte.'): Promise<boolean> {
  if (status().unlocked) return true;
  return new Promise<boolean>((resolve) => {
    let close = () => {};
    const pad = new PinPad({
      title: 'Forældre-PIN',
      subtitle: reason,
      onCancel: () => finish(false),
      onSubmit: async (pin) => {
        try {
          const r = await VolumeLimiter.verifyPin({ pin });
          if (r.ok) {
            await refresh();
            finish(true);
            return null;
          }
          if (r.lockoutSeconds > 0) {
            pad.lockFor(r.lockoutSeconds);
            return lockoutText(r.lockoutSeconds);
          }
          return r.attemptsLeft === 1 ? 'Forkert PIN-kode. 1 forsøg tilbage.' : `Forkert PIN-kode. ${r.attemptsLeft} forsøg tilbage.`;
        } catch (err) {
          return errorMessage(err);
        }
      },
    });
    function finish(ok: boolean) {
      pad.destroy();
      close();
      resolve(ok);
    }
    close = openModal(pad.root, () => finish(false));
  });
}

/**
 * Lader forælderen vælge en ny PIN-kode (indtast + gentag).
 * `save` gemmer koden; returnér en fejltekst for at prøve igen.
 */
export function newPinPad(save: (pin: string) => Promise<string | null>, onCancel?: () => void): PinPad {
  let first: string | null = null;
  const pad: PinPad = new PinPad({
    title: 'Vælg en PIN-kode',
    subtitle: 'Brug 4–6 cifre, som dit barn ikke kender.',
    onCancel,
    onSubmit: async (pin) => {
      if (first === null) {
        first = pin;
        pad.setText('Gentag PIN-koden', 'Tast den samme kode igen for at bekræfte.');
        pad.reset();
        return null;
      }
      if (pin !== first) {
        first = null;
        pad.setText('Vælg en PIN-kode', 'Brug 4–6 cifre, som dit barn ikke kender.');
        return 'Koderne var ikke ens. Prøv igen.';
      }
      return save(pin);
    },
  });
  return pad;
}

/** Skift PIN-kode fra indstillingerne (kræver, at der allerede er låst op). */
export function changePinDialog(): Promise<boolean> {
  return new Promise((resolve) => {
    let close = () => {};
    const pad = newPinPad(
      async (pin) => {
        try {
          await VolumeLimiter.changePin({ pin });
          accept(await VolumeLimiter.getStatus());
          close();
          resolve(true);
          return null;
        } catch (err) {
          return errorMessage(err);
        }
      },
      () => {
        close();
        resolve(false);
      },
    );
    close = openModal(pad.root);
  });
}
