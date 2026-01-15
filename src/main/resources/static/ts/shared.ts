/**
 * Gemeinsame Utilities für alle Seiten
 */

export function getById<T extends HTMLElement>(id: string): T {
  const el = document.getElementById(id);
  if (!el) {
    throw new Error(`Element mit ID "${id}" nicht gefunden`);
  }
  return el as T;
}

export function setMessage(element: HTMLElement, message: string, type: 'success' | 'error' | 'info' = 'info') {
  element.textContent = message;
  element.className = `message message-${type}`;
}

export function onReady(callback: () => void) {
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', callback);
  } else {
    callback();
  }
}
