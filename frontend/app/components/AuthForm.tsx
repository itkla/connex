"use client";

import Link from "next/link";
import { redirect, useRouter } from "next/navigation";
import { useState } from "react";
import { toast } from "sonner";
import { useTranslations } from "next-intl";

import { ApiError, login, register as registerUser } from "@/app/lib/api";
import { LoaderCircle } from "lucide-react";

type AuthMode = "login" | "register";
type FieldKey = "username" | "email" | "displayName" | "password";
type FieldErrors = Partial<Record<FieldKey, string>>;

const FIELD_KEYS = ["username", "email", "displayName", "password"] as const;

const FORM_FIELDS: Record<AuthMode, FieldKey[]> = {
    login: ["username", "password"],
    register: ["username", "email", "displayName", "password"],
};

const ALT_HREF: Record<AuthMode, string> = {
    login: "/auth/register",
    register: "/auth/login",
};

const FIELD_META: Record<
    FieldKey,
    { type: string; autoComplete: string }
> = {
    username: {
        type: "text",
        autoComplete: "username",
    },
    email: { type: "email", autoComplete: "email" },
    displayName: {
        type: "text",
        autoComplete: "name",
    },
    password: {
        type: "password",
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

export function AuthForm({ mode, redirectUrl }: { mode: AuthMode, redirectUrl: string | null }) {
    const router = useRouter();
    const tForm = useTranslations("AuthForm");
    const tMode = useTranslations(mode === "login" ? "AuthLogin" : "AuthRegister");
    const tLogin = useTranslations("AuthLogin");

    const fields = FORM_FIELDS[mode];
    const altHref = ALT_HREF[mode];

    const fieldPlaceholders: Record<FieldKey, string> = {
        username: tForm("placeholderUsername"),
        email: tForm("placeholderEmail"),
        displayName: tForm("placeholderDisplayName"),
        password: tForm("placeholderPassword"),
    };

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

    function getAuthErrorMessage(
        err: ApiError,
        hasFieldErrors: boolean,
    ) {
        if (mode === "login" && err.status === 401) {
            return tLogin("invalidCredentials");
        }

        if (hasFieldErrors) {
            return tForm("formHasErrors");
        }

        return err.message;
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

            toast.success(tMode("successMessage"), {
                style: {
                    backgroundColor: "var(--color-brand)",
                    color: "white",
                }
            });
            // router.replace("/dashboard");
            if (redirectUrl) {
                router.push(redirectUrl);
            } else {
                router.replace("/dashboard");
            }
            router.refresh();
        } catch (err) {
            const nextFieldErrors = err instanceof ApiError ? pickFieldErrors(err.fieldErrors) : {};
            const hasFieldErrors = Object.keys(nextFieldErrors).length > 0;
            const message = err instanceof ApiError ? getAuthErrorMessage(err, hasFieldErrors) : tForm("genericError");

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
                        {tMode("heading")}
                    </span>
                    <span className="mt-2 block text-5xl font-extrabold tracking-tight text-black">
                        <Link href="/">{tForm("brand")}</Link>
                    </span>
                </h1>

                <form className="mt-12 space-y-3" onSubmit={onSubmit} noValidate>
                    {fields.map((key) => {
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
                                    placeholder={fieldPlaceholders[key]}
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
                                ? tForm("resolveErrors")
                                : tMode("submitLabel")}
                    </button>

                    <hr className="mx-auto mt-6 w-3/5 border-neutral-200" />

                    <div className="mt-6 text-center">
                        <Link
                            href={altHref}
                            className="text-base text-brand hover:text-brand-hover"
                        >
                            {tMode("altLabel")}
                        </Link>
                    </div>
                </form>
            </div>
        </div>
    );
}
