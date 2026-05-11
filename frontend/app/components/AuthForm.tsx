"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";

import { ApiError, login, register as registerUser } from "@/app/lib/api";
import { LoaderCircle } from "lucide-react";

type AuthMode = "login" | "register";
type FieldKey = "username" | "email" | "displayName" | "password";
type FieldErrors = Partial<Record<FieldKey, string>>;

const FIELD_KEYS = ["username", "email", "displayName", "password"] as const;

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
        placeholder: "Name",
        autoComplete: "name",
    },
    password: {
        type: "password",
        placeholder: "Password",
        autoComplete: "current-password",
    },
};

function pickFieldErrors(errors?: Record<string, string>): FieldErrors {
    if (!errors) {
        return {};
    }

    return FIELD_KEYS.reduce<FieldErrors>((picked, key) => {
        if (errors[key]) {
            picked[key] = errors[key];
        }

        return picked;
    }, {});
}

function getAuthErrorMessage(
    error: ApiError,
    mode: AuthMode,
    hasFieldErrors: boolean,
) {
    if (mode === "login" && error.status === 401) {
        return "Invalid username or password";
    }

    if (hasFieldErrors) {
        // return "Please fix the highlighted fields.";
        return "There is an error in the form. Please check and correct the highlighted fields.";
    }

    return error.message;
}

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
    const [fieldErrors, setFieldErrors] = useState<FieldErrors>({});
    const [submitting, setSubmitting] = useState(false);

    function setField(key: FieldKey, value: string) {
        setValues((prev) => ({ ...prev, [key]: value }));
        setError(null);
        setFieldErrors((prev) => {
            if (!prev[key]) {
                return prev;
            }

            const next = { ...prev };
            delete next[key];
            return next;
        });
    }

    async function onSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setError(null);
        setFieldErrors({});
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
                style: {
                    backgroundColor: "var(--color-brand)",
                    color: "white",
                }
            });
            router.replace("/dashboard");
            router.refresh();
        } catch (err) {
            const nextFieldErrors = err instanceof ApiError ? pickFieldErrors(err.fieldErrors) : {};
            const hasFieldErrors = Object.keys(nextFieldErrors).length > 0;
            const message = err instanceof ApiError ? getAuthErrorMessage(err, mode, hasFieldErrors) : "Something went wrong";

            setError(message);
            setFieldErrors(nextFieldErrors);
            toast.error(message, {
                style: {
                    backgroundColor: "var(--color-destructive)",
                    color: "white",
                }
            });
        } finally {
            setSubmitting(false);
        }
    }

    const hasFieldErrors = Object.keys(fieldErrors).length > 0;

    return (
        <div className="flex min-h-screen items-start justify-center bg-white px-6 pt-24 pb-12">
            <div className="w-full max-w-md">
                <h1 className="text-center leading-tight tracking-tight">
                    <span className="block font-['Instrument_Serif'] text-5xl text-black">
                        {config.heading}
                    </span>
                    <span className="mt-2 block text-5xl font-extrabold tracking-tight text-black">
                        <Link href="/">CONNEX</Link>
                    </span>
                </h1>

                <form className="mt-12 space-y-3" onSubmit={onSubmit} noValidate>
                    {config.fields.map((key) => {
                        const meta = FIELD_META[key];
                        const autoComplete = key === "password" && mode === "register" ? "new-password" : meta.autoComplete;
                        const fieldError = fieldErrors[key];
                        const errorId = `${mode}-${key}-error`;

                        return (
                            <div key={key} className="space-y-1">
                                <input
                                    type={meta.type}
                                    value={values[key]}
                                    onChange={(e) => setField(key, e.target.value)}
                                    placeholder={meta.placeholder}
                                    autoComplete={autoComplete}
                                    required
                                    aria-invalid={Boolean(fieldError)}
                                    aria-describedby={fieldError ? errorId : undefined}
                                    className={`w-full rounded-xl bg-neutral-200 px-6 py-4 text-base text-black placeholder-neutral-500 outline-none transition focus:ring-2 focus:ring-brand focus:ring-offset-white ${fieldError ? "ring-2 ring-red-500" : ""}`}
                                />
                                {fieldError && (
                                    <p id={errorId} className="px-2 text-sm text-red-600">
                                        {fieldError}
                                    </p>
                                )}
                            </div>
                        );
                    })}

                    {error && !hasFieldErrors && (
                        <p className="text-center text-sm text-red-600 transition">{error}</p>
                    )}

                    <button
                        type="submit"
                        disabled={submitting || hasFieldErrors}
                        className={`w-full rounded-xl px-6 py-4 text-base font-medium text-white shadow-sm transition disabled:cursor-not-allowed ${
                            hasFieldErrors
                                ? "disabled:bg-gray-300 disabled:text-gray-500 "
                                : "bg-brand hover:bg-brand-hover disabled:opacity-60"
                        }`}
                    >
                        {submitting
                            ? (
                                <span className="flex justify-center items-center w-full">
                                    <LoaderCircle className="size-4 animate-spin text-white" />
                                </span>
                            )
                            : hasFieldErrors
                                ? "Please resolve the errors"
                                : config.submitLabel}
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
