"use client";

import SegmentedToggle from "./SegmentedToggle";

export type SortToggleOption<T extends string> = {
    value: T;
    label: string;
    icon?: React.ReactNode;
};

export default function SortToggle<T extends string>({
    value,
    onChange,
    options,
    className,
}: {
    value: T;
    onChange: (v: T) => void;
    options: SortToggleOption<T>[];
    className?: string;
}) {
    return (
        <SegmentedToggle
            value={value}
            onChange={onChange}
            className={className}
            options={options.map((o) => ({ value: o.value, label: o.label, icon: o.icon }))}
        />
    );
}
