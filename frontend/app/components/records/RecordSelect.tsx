'use client';

import { UserIcon } from '@heroicons/react/24/outline';

import { Select, SelectItem, SelectContent, SelectValue, SelectTrigger } from '@/components/ui/select';
import { Avatar, AvatarFallback, AvatarImage } from '@/components/ui/avatar';

export type RecordSelectOption = {
    id: number;
    label: string;
    imageUrl?: string | null;
};

export default function RecordSelect({
    options,
    value,
    onValueChange,
    placeholder,
    id,
    className,
    disabled,
    noneOption,
}: {
    options: RecordSelectOption[];
    value: string;
    onValueChange: (value: string) => void;
    placeholder?: string;
    id?: string;
    className?: string;
    disabled?: boolean;
    noneOption?: { value: string; label: string };
}) {
    return (
        <Select value={value} onValueChange={onValueChange} disabled={disabled}>
            <SelectTrigger id={id} className={className}>
                <SelectValue placeholder={placeholder} />
            </SelectTrigger>
            <SelectContent>
                {noneOption && <SelectItem value={noneOption.value}>{noneOption.label}</SelectItem>}
                {options.map((option) => (
                    <SelectItem key={option.id} value={option.id.toString()}>
                        <Avatar>
                            <AvatarImage src={option.imageUrl ?? undefined} />
                            <AvatarFallback>
                                <UserIcon className="size-4" />
                            </AvatarFallback>
                        </Avatar>
                        {option.label}
                    </SelectItem>
                ))}
            </SelectContent>
        </Select>
    );
}