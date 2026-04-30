"use client";

import { useState, use } from "react";
import { notFound, useRouter } from "next/navigation";

import { login, register } from "../../lib/api";

const ACTIONS = {
  login: {
    heading: "Sign in to",
    submitLabel: "Sign in",
    fields: ["username", "password"] as const,
    altHref: "/auth/signup",
    altLabel: "Create an account",
  },
  signup: {
    heading: "Sign up for",
    submitLabel: "Create account",
    fields: ["username", "email", "displayName", "password"] as const,
    altHref: "/auth/login",
    altLabel: "Sign in instead",
  },
} as const;

type Action = keyof typeof ACTIONS;
type FieldKey = "username" | "email" | "displayName" | "password";

const FIELD_META: Record<
  FieldKey,
  { type: string; placeholder: string; autoComplete: string }
> = {
  username: {
    type: "text",
    placeholder: "Username",
    autoComplete: "username",
  },
  email: { type: "email", placeholder: "Email", autoComplete: "email" },
  displayName: {
    type: "text",
    placeholder: "Display name",
    autoComplete: "name",
  },
  password: {
    type: "password",
    placeholder: "Password",
    autoComplete: "current-password",
  },
};

function isAction(value: string): value is Action {
  return value in ACTIONS;
}

export default function AuthPage({
  params,
}: {
  params: Promise<{ action: string }>;
}) {
  const router = useRouter();
  const { action } = use(params);

  const [values, setValues] = useState<Record<FieldKey, string>>({
    username: "",
    email: "",
    displayName: "",
    password: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  if (!isAction(action)) {
    notFound();
  }
  const config = ACTIONS[action];

  function setField(key: FieldKey, value: string) {
    setValues((prev) => ({ ...prev, [key]: value }));
  }

  async function onSubmit(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    setSubmitting(true);
    try {
      if (action === "login") {
        await login({
          username: values.username,
          password: values.password,
        });
      } else {
        await register({
          username: values.username,
          email: values.email,
          displayName: values.displayName,
          password: values.password,
        });
      }
      router.push("/");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong ???");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-white px-6">
      <div className="w-full max-w-md">
        <h1 className="text-center leading-tight tracking-tight">
          <span className="block font-['Instrument_Serif'] text-5xl text-black">
            {config.heading}
          </span>
          <span className="mt-2 block text-5xl font-extrabold tracking-tight text-black">
            <a href="/">CONNEX</a>
          </span>
        </h1>

        <form className="mt-12 space-y-3" onSubmit={onSubmit}>
          {config.fields.map((key) => {
            const meta = FIELD_META[key];
            const autoComplete =
              key === "password" && action === "signup"
                ? "new-password"
                : meta.autoComplete;
            return (
              <input
                key={key}
                type={meta.type}
                value={values[key]}
                onChange={(e) => setField(key, e.target.value)}
                placeholder={meta.placeholder}
                autoComplete={autoComplete}
                required
                className="w-full rounded-xl bg-neutral-200 px-6 py-4 text-base text-black placeholder-neutral-500 outline-none focus:ring-2 focus:ring-brand focus:ring-offset-white transition"
              />
            );
          })}

          {error && (
            <p className="text-center text-sm text-red-600">{error}</p>
          )}

          <button
            type="submit"
            disabled={submitting}
            className="w-full rounded-xl bg-brand px-6 py-4 text-base font-medium text-white shadow-sm transition hover:bg-brand-hover disabled:cursor-not-allowed disabled:opacity-60"
          >
            {submitting ? "Please wait..." : config.submitLabel}
          </button>

          <hr className="mx-auto mt-6 w-3/5 border-neutral-200" />

          <div className="mt-6 text-center">
            <a
              href={config.altHref}
              className="text-base text-brand hover:text-brand-hover"
            >
              {config.altLabel}
            </a>
          </div>
        </form>
      </div>
    </div>
  );
}
