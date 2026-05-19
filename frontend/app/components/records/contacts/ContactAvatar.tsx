import { type Contact } from "@/app/lib/types";
import { cn } from "@/lib/utils";
import { UserIcon } from "lucide-react";

export default function ContactAvatar({ contact, type = 'small', upload = false }: { contact: Contact, type?: 'small' | 'medium' | 'large', upload?: boolean }) {
    // if (upload) {
    //     return (
    //         <div className="h-8 w-8 overflow-hidden rounded-full bg-neutral-200 ring-1 ring-black/5">
    //             <input type="file" accept="image/*" onChange={handleImageChange} className="sr-only" />
    //         </div>
    //     )
    // }
    return (
        // ContactAvatars are always round. company logos are squircles
        <div className={cn("h-8 w-8 overflow-hidden rounded-full bg-neutral-200 ring-1 ring-black/5", type === 'small' ? 'h-8 w-8' : type === 'medium' ? 'h-12 w-12' : 'h-16 w-16')}>
            {contact.imageUrl ? (
                <img src={contact.imageUrl} alt="" className="h-full w-full object-cover" />
            ) : (
                <div className="h-full w-full flex items-center justify-center bg-gray-400">
                    {
                        type === 'small' ? (
                            <UserIcon className="size-4 text-white" />
                        ) : type === 'medium' ? (
                            <UserIcon className="size-8 text-white" />
                        ) : (
                            <UserIcon className="size-10 text-white" />
                        )
                    }
                </div>
            )}
        </div>
    )
}