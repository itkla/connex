"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { useTranslations } from "next-intl";
import { PlusIcon } from "@heroicons/react/24/solid";
import { Loader2Icon } from "lucide-react";

import {
    Dialog,
    DialogClose,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
} from "@/components/ui/dialog";
import { Button } from "@/components/ui/button";
import { Label } from "@/components/ui/label";
import { ApiError, createUser } from "@/app/lib/api";
import { toastError, toastSuccess } from "@/app/lib/toast";
import { type RegisterPayload } from "@/app/lib/types";

const inputClass =
    "w-full rounded-lg bg-neutral-100 px-3 py-2 text-sm text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand";

const EMPTY: RegisterPayload = { displayName: "", username: "", email: "", password: "" };

function Field({
    id,
    label,
    type,
    value,
    onChange,
    placeholder,
    error,
    autoFocus,
}: {
    id: string;
    label: string;
    type: string;
    value: string;
    onChange: (value: string) => void;
    placeholder?: string;
    error?: string;
    autoFocus?: boolean;
}) {
    return (
        <div className="grid gap-1.5">
            <Label htmlFor={id}>{label}</Label>
            <input
                id={id}
                type={type}
                value={value}
                onChange={(e) => onChange(e.target.value)}
                className={inputClass}
                placeholder={placeholder}
                autoFocus={autoFocus}
                aria-invalid={error ? true : undefined}
            />
            {error && <p className="text-xs text-destructive">{error}</p>}
        </div>
    );
}

export default function NewUserDialog() {
    const t = useTranslations("UsersNewUserDialog");
    const router = useRouter();
    const [open, setOpen] = useState(false);
    const [payload, setPayload] = useState<RegisterPayload>(EMPTY);
    const [errors, setErrors] = useState<Record<string, string>>({});
    const [isCreating, setIsCreating] = useState(false);

    const set = (patch: Partial<RegisterPayload>) => setPayload((prev) => ({ ...prev, ...patch }));

    const onOpenChange = (next: boolean) => {
        setOpen(next);
        if (!next) {
            setPayload(EMPTY);
            setErrors({});
        }
    };

    const canSubmit =
        payload.displayName.trim() &&
        payload.username.trim() &&
        payload.email.trim() &&
        payload.password.length > 0;

    const submit = async () => {
        setIsCreating(true);
        setErrors({});
        try {
            await createUser({
                displayName: payload.displayName.trim(),
                username: payload.username.trim(),
                email: payload.email.trim(),
                password: payload.password,
            });
            toastSuccess(t("toastCreated"));
            onOpenChange(false);
            router.refresh();
        } catch (err) {
            if (err instanceof ApiError && err.fieldErrors) {
                setErrors(err.fieldErrors);
            } else {
                toastError(err instanceof Error ? err.message : t("toastFailed"));
            }
        } finally {
            setIsCreating(false);
        }
    };

    return (
        <Dialog open={open} onOpenChange={onOpenChange}>
            <DialogTrigger asChild>
                <Button className="bg-brand text-white" aria-label={t("newAria")}>
                    <PlusIcon strokeWidth={2.5} />
                    {t("new")}
                </Button>
            </DialogTrigger>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>{t("dialogTitle")}</DialogTitle>
                    <DialogDescription>{t("description")}</DialogDescription>
                </DialogHeader>

                <div className="grid gap-4">
                    <Field
                        id="displayName"
                        label={t("displayName")}
                        type="text"
                        value={payload.displayName}
                        onChange={(v) => set({ displayName: v })}
                        placeholder={t("displayNamePlaceholder")}
                        error={errors.displayName}
                        autoFocus
                    />
                    <Field
                        id="username"
                        label={t("username")}
                        type="text"
                        value={payload.username}
                        onChange={(v) => set({ username: v })}
                        placeholder={t("usernamePlaceholder")}
                        error={errors.username}
                    />
                    <Field
                        id="email"
                        label={t("email")}
                        type="email"
                        value={payload.email}
                        onChange={(v) => set({ email: v })}
                        placeholder={t("emailPlaceholder")}
                        error={errors.email}
                    />
                    <Field
                        id="password"
                        label={t("password")}
                        type="password"
                        value={payload.password}
                        onChange={(v) => set({ password: v })}
                        placeholder={t("passwordPlaceholder")}
                        error={errors.password}
                    />
                </div>

                <DialogFooter>
                    <DialogClose asChild>
                        <Button variant="outline" disabled={isCreating}>
                            {t("cancel")}
                        </Button>
                    </DialogClose>
                    <Button
                        onClick={submit}
                        disabled={isCreating || !canSubmit}
                        className="bg-brand text-white hover:bg-brand-dark"
                    >
                        {isCreating ? <Loader2Icon className="size-4 animate-spin" /> : t("create")}
                    </Button>
                </DialogFooter>
            </DialogContent>
        </Dialog>
    );
}