"use client";

import { SegmentedControl } from "@/components/ui/segmented-control";

export type SortToggleOption<T extends string> = {
    value: T;
    label: string;
    icon?: React.ReactNode;
};

/**
 * Sort-order switch for a browser toolbar. A thin naming layer over the canonical
 * {@link SegmentedControl}: sorting is mode switching, so it uses the same control as every other
 * mode switch in the product.
 */
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
        <SegmentedControl
            value={value}
            onChange={onChange}
            className={className}
            options={options.map((o) => ({ value: o.value, label: o.label, icon: o.icon }))}
        />
    );
}
