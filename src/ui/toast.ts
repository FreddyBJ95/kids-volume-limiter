import { el } from './dom';

let host: HTMLElement | null = null;

/** Kort besked nederst på skærmen. */
export function toast(message: string, kind: 'info' | 'error' = 'info') {
  host ??= document.body.appendChild(el('div', { class: 'toast-host', role: 'status' }));
  const node = el('div', { class: `toast toast-${kind}` }, message);
  host.appendChild(node);
  requestAnimationFrame(() => node.classList.add('show'));
  setTimeout(() => {
    node.classList.remove('show');
    setTimeout(() => node.remove(), 250);
  }, 2800);
}
