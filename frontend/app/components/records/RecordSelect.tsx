'use client';

import { UserIcon } from '@heroicons/react/24/outline';

import {
    Combobox,
    ComboboxContent,
    ComboboxEmpty,
    ComboboxInput,
    ComboboxItem,
    ComboboxList,
} from '@/components/ui/combobox';
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
    onInputValueChange,
    emptyLabel,
}: {
    options: RecordSelectOption[];
    value: string;
    onValueChange: (value: string) => void;
    placeholder?: string;
    id?: string;
    className?: string;
    disabled?: boolean;
    noneOption?: { value: string; label: string };
    onInputValueChange?: (value: string) => void;
    emptyLabel?: string;
}) {
    const items = [
        ...(noneOption ? [{
            id: noneOption.value,
            label: noneOption.label,
            imageUrl: null,
            isNone: true,
        }] : []),
        ...options.map((option) => ({
            id: option.id.toString(),
            label: option.label,
            imageUrl: option.imageUrl,
            isNone: false,
        })),
    ];
    const selected = items.find((item) => item.id === value) ?? null;

    return (
        <Combobox<(typeof items)[number]>
            items={items}
            filter={onInputValueChange ? null : undefined}
            itemToStringLabel={(item) => item.label}
            value={selected}
            onInputValueChange={onInputValueChange}
            onValueChange={(item) => {
                if (item) onValueChange(item.id);
            }}
            disabled={disabled}
        >
            <ComboboxInput
                id={id}
                disabled={disabled}
                placeholder={placeholder}
                className={className}
            />
            <ComboboxContent className="pointer-events-auto">
                <ComboboxList>
                    <ComboboxEmpty>{emptyLabel ?? placeholder}</ComboboxEmpty>
                    {items.map((item) => (
                        <ComboboxItem key={item.id} value={item}>
                            {!item.isNone ? (
                                <Avatar>
                                    <AvatarImage src={item.imageUrl ?? undefined} />
                                    <AvatarFallback>
                                        <UserIcon className="size-4" />
                                    </AvatarFallback>
                                </Avatar>
                            ) : null}
                            {item.label}
                        </ComboboxItem>
                    ))}
                </ComboboxList>
            </ComboboxContent>
        </Combobox>
    );
}
