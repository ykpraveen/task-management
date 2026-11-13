import { createFormHook, createFormHookContexts } from '@tanstack/react-form';

/**
 * App-wide TanStack Form setup. Field and form contexts let shared field components (labeled
 * inputs with aria-invalid/aria-describedby, the error summary) read form state without prop
 * drilling. Field and form components are registered here as the stories add them.
 */
export const { fieldContext, formContext, useFieldContext, useFormContext } = createFormHookContexts();

export const { useAppForm, withForm } = createFormHook({
  fieldContext,
  formContext,
  fieldComponents: {},
  formComponents: {},
});
