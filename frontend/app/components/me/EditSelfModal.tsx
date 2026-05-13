'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { LoaderCircle } from 'lucide-react';
import { PencilSquareIcon } from '@heroicons/react/16/solid';
import { toast } from 'sonner';

import {
    Dialog,
    DialogContent,
    DialogDescription,
    DialogFooter,
    DialogHeader,
    DialogTitle,
    DialogTrigger,
    DialogClose,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';
import { Label } from '@/components/ui/label';

import { ApiError, updateUser, type User } from '@/app/lib/api';

const inputClass = 'w-full rounded-xl bg-neutral-100 px-4 py-2.5 text-base text-black placeholder-neutral-500 outline-none ring-1 ring-black/5 transition focus:ring-2 focus:ring-brand';

type Props = {
    user: User;
};

export default function EditSelfModal({ user }: Props) {
    const router = useRouter();
    const [open, setOpen] = useState(false);
    const [username, setUsername] = useState(user.username);
    const [displayName, setDisplayName] = useState(user.displayName);
    const [email, setEmail] = useState(user.email);
    const [profilePicture, setProfilePicture] = useState<File | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

    function reset() {
        setUsername(user.username);
        setDisplayName(user.displayName);
        setEmail(user.email);
        setProfilePicture(null);
        setFieldErrors({});
    }

    async function uploadProfilePicture(file: File): Promise<string> {
        const formData = new FormData();
        formData.append('profilePicture', file);
        const res = await fetch('/api/users/me/profile-picture', {
            method: 'PUT',
            body: formData,
        });
        if (!res.ok) {
            throw new Error('Failed to upload profile picture');
        }
        const data = (await res.json()) as { profilePictureUrl: string };
        return data.profilePictureUrl;
    }

    async function handleSubmit(e: React.SubmitEvent<HTMLFormElement>) {
        e.preventDefault();
        setSubmitting(true);
        setFieldErrors({});

        // check if text fields were changed
        const textChanged =
            username !== user.username ||
            displayName !== user.displayName ||
            email !== user.email;

        // check if pfp was changed
        const pictureChanged = profilePicture !== null;

        // if nothing changed, close the modal and stop the submission
        if (!textChanged && !pictureChanged) {
            toast.info('No changes were made', {
                // description: 'Please make changes to your profile to update it.',
            });
            setOpen(false);
            setSubmitting(false);
            return;
        }

        try {
            let userWasUpdated = false;
            // check if text fields were changed and update them if so + update the user with the new text fields
            if (textChanged) {
                await updateUser(user.id, {
                    username,
                    displayName,
                    email,
                    profilePictureUrl: user.profilePictureUrl,
                });
                userWasUpdated = true;
            }
            // check if pfp was changed and upload it if so + update the user with the new pfp url
            if (pictureChanged) {
                const profilePictureUrl = await uploadProfilePicture(profilePicture);
                await updateUser(user.id, {
                    username,
                    displayName,
                    email,
                    profilePictureUrl,
                });
                userWasUpdated = true;
            }

            if (userWasUpdated) {
                toast.success('Profile updated', {
                    description: 'Your profile has been updated successfully.',
                    style: {
                        backgroundColor: "var(--color-brand)",
                        color: "white",
                    }
                });
                setOpen(false);
                router.refresh();
            } else {
                toast.error('An error occurred while updating your profile', {
                    description: 'Please try again.',
                    style: {
                        backgroundColor: "var(--color-destructive)",
                        color: "white",
                    }
                });
            }
        } catch (err) {
            if (err instanceof ApiError) {
                if (err.fieldErrors) setFieldErrors(err.fieldErrors);
                toast.error(err.message, {
                    // description: err.fieldErrors ? 'Please fix the highlighted fields.' : err.message,
                    style: {
                        backgroundColor: "var(--color-destructive)",
                        color: "white",
                    }
                });
            } else {
                toast.error(err instanceof Error ? err.message : 'Failed to update profile');
            }
        } finally {
            setSubmitting(false);
        }
    }

    return (
        <Dialog
            open={open}
            onOpenChange={(next) => {
                setOpen(next);
                if (!next) reset();
            }}
        >
            <DialogTrigger asChild>
                <Button
                    type="button"
                    variant="ghost"
                    size="icon-sm"
                    title="Edit profile"
                    className="text-neutral-500 hover:text-black cursor-pointer"
                >
                    <PencilSquareIcon className="size-5" />
                    <span className="sr-only">Edit Profile</span>
                </Button>
            </DialogTrigger>
            <DialogContent>
                <DialogHeader>
                    <DialogTitle>Edit your profile</DialogTitle>
                    <DialogDescription>
                        Update your account details. Changes save immediately.
                    </DialogDescription>
                </DialogHeader>

                <form onSubmit={handleSubmit} className="grid gap-4">
                    <div className="grid gap-2">
                        <Label htmlFor="profile-picture">Profile picture</Label>
                        {/* TODO: hide the input field, move the preview to the left, make it clickable, and move the display name to be inline with the preview. */}
                        <div className="flex items-center gap-3">
                            <input
                                id="profile-picture"
                                type="file"
                                accept="image/*"
                                onChange={(e) =>
                                    setProfilePicture(e.target.files?.[0] ?? null)
                                }
                                className="text-sm"
                            />
                            {(profilePicture || user.profilePictureUrl) && (
                                <img
                                    src={
                                        profilePicture
                                            ? URL.createObjectURL(profilePicture)
                                            : user.profilePictureUrl
                                    }
                                    alt=""
                                    className="h-24 w-24 rounded-full object-cover ring-1 ring-black/5"
                                />
                            )}
                        </div>
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="username">Username</Label>
                        <input
                            id="username"
                            type="text"
                            value={username}
                            onChange={(e) => setUsername(e.target.value)}
                            className={inputClass}
                            required
                        />
                        {fieldErrors.username && (
                            <p className="text-sm text-red-500">{fieldErrors.username}</p>
                        )}
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="displayName">Display name</Label>
                        <input
                            id="displayName"
                            type="text"
                            value={displayName}
                            onChange={(e) => setDisplayName(e.target.value)}
                            className={inputClass}
                            required
                        />
                        {fieldErrors.displayName && (
                            <p className="text-sm text-red-500">{fieldErrors.displayName}</p>
                        )}
                    </div>

                    <div className="grid gap-2">
                        <Label htmlFor="email">Email</Label>
                        <input
                            id="email"
                            type="email"
                            value={email}
                            onChange={(e) => setEmail(e.target.value)}
                            className={inputClass}
                            required
                        />
                        {fieldErrors.email && (
                            <p className="text-sm text-red-500">{fieldErrors.email}</p>
                        )}
                    </div>

                    <DialogFooter>
                        <DialogClose asChild>
                            <Button type="button" variant="outline" disabled={submitting}>
                                Cancel
                            </Button>
                        </DialogClose>
                        <Button type="submit" disabled={submitting}>
                            {submitting ? (
                                <LoaderCircle className="size-4 animate-spin" />
                            ) : (
                                'Save'
                            )}
                        </Button>
                    </DialogFooter>
                </form>
            </DialogContent>
        </Dialog>
    );
}
