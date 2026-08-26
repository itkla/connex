'use client';

import CustomRangePopover, {
    type CustomRangeLabels,
} from '@/app/components/overview/analytics/CustomRangePopover';
import type { AnalyticsWindow } from '@/app/components/overview/analytics/metrics';
import type { MemberScopeParams } from '@/app/lib/types';
import { SegmentedControl } from '@/components/ui/segmented-control';

/**
 * Time-range and granularity switch for the analytics board, on the canonical
 * {@link SegmentedControl}. The optional custom-range segment is the control's one `render`
 * escape hatch: the popover trigger *is* a segment, so it takes the shared travelling thumb
 * rather than drawing a second selected state beside it.
 */
export default function RangeControl<K extends string>({
    value,
    onChange,
    options,
    customRange,
    label,
    layoutId = 'analytics-range-thumb',
}: {
    value: K;
    onChange: (next: K) => void;
    options: { key: K; label: string }[];
    customRange?: {
        key: K;
        value: AnalyticsWindow;
        locale: string;
        today: string;
        timezone: string;
        scope: MemberScopeParams;
        labels: CustomRangeLabels;
        onApply: (window: AnalyticsWindow) => void;
    };
    label: string;
    layoutId?: string;
}) {
    return (
        <SegmentedControl<K>
            ariaLabel={label}
            value={value}
            onChange={onChange}
            layoutId={layoutId}
            options={[
                ...options.map((opt) => ({ value: opt.key, label: opt.label })),
                ...(customRange
                    ? [
                          {
                              value: customRange.key,
                              label: customRange.labels.custom,
                              render: ({
                                  active,
                                  className,
                                  thumb,
                              }: {
                                  active: boolean;
                                  className: string;
                                  thumb: React.ReactNode;
                              }) => (
                                  <CustomRangePopover
                                      active={active}
                                      value={customRange.value}
                                      locale={customRange.locale}
                                      today={customRange.today}
                                      timezone={customRange.timezone}
                                      scope={customRange.scope}
                                      labels={customRange.labels}
                                      className={className}
                                      thumb={thumb}
                                      onApply={customRange.onApply}
                                  />
                              ),
                          },
                      ]
                    : []),
            ]}
        />
    );
}
