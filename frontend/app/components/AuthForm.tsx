"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { login, register as registerUser } from "@/app/lib/api";

type AuthMode = "login" | "register";
type FieldKey = "username" | "email" | "displayName" | "password";

const FORM_CONFIG: Record<
    AuthMode,
    {
        heading: string;
        submitLabel: string;
        successMessage: string;
        fields: FieldKey[];
        altHref: string;
        altLabel: string;
    }
> = {
    login: {
        heading: "Sign in to",
        submitLabel: "Sign in",
        successMessage: "You are now logged in",
        fields: ["username", "password"],
        altHref: "/auth/register",
        altLabel: "Create an account",
    },
    register: {
        heading: "Sign up for",
        submitLabel: "Create account",
        successMessage: "Account created successfully",
        fields: ["username", "email", "displayName", "password"],
        altHref: "/auth/login",
        altLabel: "Sign in instead",
    },
};

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

export function AuthForm({ mode }: { mode: AuthMode }) {
    const router = useRouter();
    const config = FORM_CONFIG[mode];
    const [values, setValues] = useState<Record<FieldKey, string>>({
        username: "",
        email: "",
        displayName: "",
        password: "",
    });
    const [error, setError] = useState<string | null>(null);
    const [submitting, setSubmitting] = useState(false);

    function setField(key: FieldKey, value: string) {
        setValues((prev) => ({ ...prev, [key]: value }));
    }

    async function onSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setError(null);
        setSubmitting(true);

        try {
            if (mode === "login") {
                await login({
                    username: values.username,
                    password: values.password,
                });
            } else {
                await registerUser({
                    username: values.username,
                    email: values.email,
                    displayName: values.displayName,
                    password: values.password,
                });
            }

            toast.success(config.successMessage, {
                // unstyled: true,
                style: {
                    backgroundColor: "#73d200",
                    color: "white",
                }
            });
            router.replace("/dashboard");
            router.refresh();
        } catch (err) {
            const message = err instanceof Error ? err.message : "Something went wrong";
            setError(message);
            toast.error(message, {
                // unstyled: true,
                style: {
                    backgroundColor: "--color-destructive",
                    color: "--color-destructive-foreground",
                }
            });
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
                        <Link href="/">CONNEX</Link>
                    </span>
                </h1>

                <form className="mt-12 space-y-3" onSubmit={onSubmit}>
                    {config.fields.map((key) => {
                        const meta = FIELD_META[key];
                        const autoComplete =
                            key === "password" && mode === "register"
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
                                className="w-full rounded-xl bg-neutral-200 px-6 py-4 text-base text-black placeholder-neutral-500 outline-none transition focus:ring-2 focus:ring-brand focus:ring-offset-white"
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
                        <Link
                            href={config.altHref}
                            className="text-base text-brand hover:text-brand-hover"
                        >
                            {config.altLabel}
                        </Link>
                    </div>
                </form>
            </div>
        </div>
    );
}
