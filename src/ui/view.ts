export interface View {
  root: HTMLElement;
  destroy(): void;
}

export type Route = 'home' | 'settings';
export type Navigate = (to: Route) => void;
