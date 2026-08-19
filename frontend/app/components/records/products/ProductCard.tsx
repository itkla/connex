'use client';

import { useLocale, useTranslations } from 'next-intl';
import { EllipsisVerticalIcon, PencilIcon, TrashIcon } from '@heroicons/react/24/outline';

import {
    DropdownMenu,
    DropdownMenuContent,
    DropdownMenuItem,
    DropdownMenuSeparator,
    DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import { IconButton } from '@/components/ui/icon-button';
import { formatCurrency } from '@/app/lib/utils';
import { formatEffectiveRange, formatTaxRate } from '@/app/components/records/products/productDisplay';
import ProductAvailability from '@/app/components/records/products/ProductAvailability';
import type { Product } from '@/app/lib/types';

/**
 * One catalog entry in the products grid: what it is, what it costs, and whether it can be sold
 * today. Products have no detail route — the card's own affordances are the whole of what it opens.
 */
export default function ProductCard({
    product,
    onQuickEdit,
    onDelete,
}: {
    product: Product;
    onQuickEdit?: () => void;
    onDelete?: () => void;
}) {
    const t = useTranslations('ProductsBrowser');
    const locale = useLocale();
    const hasActions = Boolean(onQuickEdit || onDelete);

    return (
        <div className="flex h-full flex-col gap-3 rounded-2xl border border-border bg-card p-4 transition-colors hover:bg-muted/40">
            <div className="flex items-start gap-2">
                <div className="min-w-0 flex-1">
                    <p className="truncate font-medium text-foreground">{product.name}</p>
                    {product.description ? (
                        <p className="mt-1 line-clamp-2 text-sm text-muted-foreground">{product.description}</p>
                    ) : product.sku ? (
                        <p className="mt-1 truncate text-sm text-muted-foreground">{product.sku}</p>
                    ) : null}
                </div>
                <ProductAvailability
                    active={product.active}
                    activeLabel={t('active')}
                    inactiveLabel={t('inactive')}
                />
                {hasActions ? (
                    <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                            <IconButton
                                variant="ghost"
                                size="icon-toolbar"
                                label={t('actionsFor', { name: product.name })}
                            >
                                <EllipsisVerticalIcon />
                            </IconButton>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end">
                            {onQuickEdit ? (
                                <DropdownMenuItem onSelect={onQuickEdit}>
                                    <PencilIcon />
                                    {t('edit')}
                                </DropdownMenuItem>
                            ) : null}
                            {onQuickEdit && onDelete ? <DropdownMenuSeparator /> : null}
                            {onDelete ? (
                                <DropdownMenuItem variant="destructive" onSelect={onDelete}>
                                    <TrashIcon />
                                    {t('delete')}
                                </DropdownMenuItem>
                            ) : null}
                        </DropdownMenuContent>
                    </DropdownMenu>
                ) : null}
            </div>

            <div className="mt-auto flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
                <span className="text-lg font-semibold tabular-nums text-foreground">
                    {formatCurrency(product.unitPrice, product.currency, locale)}
                </span>
                <span className="text-xs text-muted-foreground">
                    {product.billingFrequency === 'recurring' ? t('recurring') : t('oneTime')}
                    {product.unit ? ` · ${t('perUnit', { unit: product.unit })}` : ''}
                </span>
            </div>
            <dl className="grid grid-cols-2 gap-x-4 gap-y-1 border-t border-border pt-3 text-xs">
                <div>
                    <dt className="text-muted-foreground">{t('columnTax')}</dt>
                    <dd className="mt-0.5 font-medium tabular-nums text-foreground">
                        {formatTaxRate(product, locale)}
                    </dd>
                </div>
                <div>
                    <dt className="text-muted-foreground">{t('effectiveDates')}</dt>
                    <dd className="mt-0.5 font-medium text-foreground">
                        {formatEffectiveRange(product, locale, t)}
                    </dd>
                </div>
            </dl>
        </div>
    );
}
