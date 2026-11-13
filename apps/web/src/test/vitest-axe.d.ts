import 'vitest';

// vitest-axe 0.1.0 augments the legacy `Vi` namespace; declare its matcher for Vitest 5.
declare module 'vitest' {
  interface Assertion<T = any> {
    toHaveNoViolations(): T;
  }
  interface AsymmetricMatchersContaining {
    toHaveNoViolations(): unknown;
  }
}
