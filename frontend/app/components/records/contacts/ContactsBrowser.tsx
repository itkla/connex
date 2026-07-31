'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { useTranslations } from 'next-intl';
import { Button } from '@/components/ui/button';
import RecordsActions from '@/app/components/import/RecordsActions';
import { ButtonGroup } from '@/components/ui/button-group';
import { toast } from 'sonner';
import { toastError, toastSuccess } from '@/app/lib/toast';
import { DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator } from '@/components/ui/dropdown-menu';
import { PencilIcon, EllipsisVerticalIcon, EyeIcon } from '@heroicons/react/24/solid';
import { BuildingOffice2Icon, NoSymbolIcon, TagIcon, UserCircleIcon, ArchiveBoxIcon, ArchiveBoxArrowDownIcon, UsersIcon } from '@heroicons/react/24/outline';
import {
    Squares2X2Icon,
    TableCellsIcon,
} from '@heroicons/react/24/outline';
import { useReducedMotion } from 'motion/react';

import RecordsRenderView from '@/app/components/records/RecordsRenderView';
import DensityToggle from '@/app/components/records/DensityToggle';
import { useRecordDensity } from '@/app/hooks/useRecordDensity';
import { useInlineEdit } from '@/app/hooks/useInlineEdit';
import ColumnVisibilityMenu from '@/app/components/records/ColumnVisibilityMenu';
import { useColumnVisibility } from '@/app/hooks/useColumnVisibility';
import type { ActiveRecordRef } from '@/app/lib/actions/types';
import { useRecordPeekController } from '@/app/components/records/useRecordPeekController';
import Rise from '@/app/components/motion/Rise';
import { useCustomFieldColumns } from '@/app/components/records/CustomFieldColumns';
import RecordsSortMenu from '@/app/components/records/RecordsSortMenu';
import RecordsFilterPills from '@/app/components/records/RecordsFilterPills';
import RecordsFilterSheet from '@/app/components/records/RecordsFilterSheet';
import { SearchField, FilterBar, SegmentedToggle, MemberScopeFilter, interpretMemberScope, MEMBER_SCOPE_ME, type FilterChipData } from '@/app/components/filters';
import ArchiveRecordDialog from '@/app/components/records/ArchiveRecordDialog';
import BulkTagDialog from '@/app/components/records/BulkTagDialog';
import { notifyBulkResult } from '@/app/lib/bulkToast';
import { useRecordsBrowser } from '@/app/hooks/useRecordsBrowser';
import { useRecordReturnSelection } from '@/app/hooks/useRecordReturnSelection';
import { useServerRecords } from '@/app/hooks/useServerRecords';
import SavedViewsBar from '@/app/components/records/SavedViewsBar';
import type { SavedView, SavedViewConfig } from '@/app/lib/types';
import { type ColumnDef, type ColumnFilterFacet, type FilterState, type SelectionId, FILTER_EMPTY, facetChips, countActiveFilters } from '@/app/components/records/types';
import ContactCard from '@/app/components/records/contacts/ContactCard';
import ContactListRow from '@/app/components/records/contacts/ContactListRow';
import ContactAvatar from '@/app/components/records/contacts/ContactAvatar';
import NewContactDialog from '@/app/components/records/contacts/NewContactDialog';
import ChangeCompanyDialog from '@/app/components/records/contacts/ChangeCompanyDialog';
import QuickEditSheet, { type ContactDraft } from '@/app/components/records/contacts/QuickEditSheet';
import { updateContact, createContact, importBusinessCard, getContactsPage, getContactTemperatures, getPersonFacets, getTags, bulkAddTagToContacts, bulkRemoveTagFromContacts, bulkArchiveContacts, bulkRestoreContacts, bulkAssignPersonOwner, getActiveWorkspaceMembers, getContactIds, exportContactsCsv, isFieldError, uploadContactPicture } from '@/app/lib/api';
import BulkAssignOwnerDialog from '@/app/components/records/BulkAssignOwnerDialog';
import { type BusinessCardImportDraft, type Contact, type UpdateContactPayload, type CreateContactPayload, type ContactsPageParams, type PersonFacets, type RelationshipTemperature, type Tag, type WorkspaceMember } from '@/app/lib/types';
import TemperaturePill from '@/app/components/records/TemperaturePill';
import { subscribeToRecordMutations } from '@/app/lib/record-mutation-events';
import {
    recordDetailNavigationPath,
    recordDetailPath,
} from '@/app/lib/recordReturnPath';

const NO_ITEMS: Contact[] = [];
const ARCHIVED_FILTER_KEY = 'archived';
const ARCHIVED_FILTER_VALUE = '1';

/** The facet filters only, with the archived-scope key the browser owns separately removed. */
function withoutArchived(state: FilterState): FilterState {
    return Object.fromEntries(
        Object.entries(state).filter(([key]) => key !== ARCHIVED_FILTER_KEY),
    );
}
const searchFields = (c: Contact) => [c.name, c.email, c.phone, c.title];
const EMPTY_CONTACT_DRAFT: CreateContactPayload = { name: '', email: '', phone: '', title: '' };

function toDraft(c: Contact): ContactDraft {
    return {
        name: c.name ?? '',
        email: c.email ?? '',
        phone: c.phone ?? '',
        title: c.title ?? '',
    };
}

function diffDraft(original: ContactDraft, draft: ContactDraft): boolean {
    return (
        original.name !== draft.name ||
        original.email !== draft.email ||
        original.phone !== draft.phone ||
        original.title !== draft.title
    );
}

export default function ContactsBrowser({ savedViews, defaultView }: { savedViews: SavedView[]; defaultView: SavedView | null }) {
    const router = useRouter();
    const t = useTranslations('ContactsBrowser');
    const tf = useTranslations('Filters');
    const ts = useTranslations('MemberScope');
    const reduce = useReducedMotion() ?? false;

    const {
        displayMode,
        effectiveDisplayMode,
        setDisplayMode,
        filterState,
        setFilterState,
        selectedIds,
        setSelectedIds,
        deleteDialogOpen,
        setDeleteDialogOpen,
    } = useRecordsBrowser<Contact>({ items: NO_ITEMS, storageKey: 'contacts:view', searchFields });
    const showArchived = filterState[ARCHIVED_FILTER_KEY]?.[0] === ARCHIVED_FILTER_VALUE;
    const setShowArchived = useCallback((next: boolean) => {
        setFilterState((current) => {
            const rest = withoutArchived(current);
            return next ? { ...rest, [ARCHIVED_FILTER_KEY]: [ARCHIVED_FILTER_VALUE] } : rest;
        });
    }, [setFilterState]);

    const ownerScope = useMemo(() => interpretMemberScope(filterState.owner), [filterState.owner]);
    const filterParams = useMemo(() => {
        const company = filterState.company ?? [];
        const titles = filterState.title ?? [];
        const companies = company.filter((k) => k !== FILTER_EMPTY);
        const params: { companies?: string[]; titles?: string[]; noCompany?: boolean; scope?: 'me' | 'members' | 'unassigned'; memberIds?: number[]; archived?: boolean } = {};
        if (companies.length) params.companies = companies;
        if (titles.length) params.titles = titles;
        if (company.includes(FILTER_EMPTY)) params.noCompany = true;
        if (ownerScope.mode !== 'all') params.scope = ownerScope.mode;
        if (ownerScope.mode === 'members') params.memberIds = ownerScope.memberIds;
        if (showArchived) params.archived = true;
        return params;
    }, [filterState, ownerScope, showArchived]);

    const {
        items: contacts,
        total,
        loading,
        page,
        setPage,
        size,
        setSize,
        query,
        setQuery,
        applyQuery,
        sortKey,
        sortDirection,
        onSortChange: changeServerSort,
        applySort: applyServerSort,
        revision,
        reload,
        patchItem,
    } = useServerRecords<Contact, ContactsPageParams>(getContactsPage, filterParams, { urlSync: true });
    const returnSelection = useRecordReturnSelection(
        'contacts',
        selectedIds,
        setSelectedIds,
        contacts,
        !loading,
    );

    const selectedContacts = useMemo(() => contacts.filter((c) => selectedIds.has(c.id)), [contacts, selectedIds]);

    const filterSignature = useMemo(() => JSON.stringify([filterParams, query]), [filterParams, query]);
    const filterSignatureRef = useRef(filterSignature);
    useEffect(() => {
        filterSignatureRef.current = filterSignature;
    }, [filterSignature]);
    const [matchedSignature, setMatchedSignature] = useState<string | null>(null);
    const allMatchingActive = selectedIds.size > 0 && matchedSignature === filterSignature;
    const selectAllRequestRef = useRef(0);
    const [selectingAll, setSelectingAll] = useState(false);
    const clearSelection = useCallback(() => {
        selectAllRequestRef.current += 1;
        setSelectingAll(false);
        setMatchedSignature(null);
        setSelectedIds(new Set());
    }, [setSelectedIds]);
    const onSortChange = useCallback((key: string) => {
        clearSelection();
        changeServerSort(key);
    }, [clearSelection, changeServerSort]);
    const applySort = useCallback((key: string | null, direction: 'asc' | 'desc') => {
        clearSelection();
        applyServerSort(key, direction);
    }, [clearSelection, applyServerSort]);
    const handleSelectedIdsChange = useCallback((ids: Set<SelectionId>) => {
        selectAllRequestRef.current += 1;
        setMatchedSignature(null);
        setSelectedIds(allMatchingActive ? new Set() : ids);
    }, [allMatchingActive, setSelectedIds]);
    const selectionScope = `${page}:${size}:${sortKey ?? ''}:${sortDirection}:${revision}:${filterSignature}`;
    const previousSelectionScopeRef = useRef(selectionScope);
    useEffect(() => {
        const scopeChanged = previousSelectionScopeRef.current !== selectionScope;
        previousSelectionScopeRef.current = selectionScope;
        if (scopeChanged && !allMatchingActive) clearSelection();
    }, [selectionScope, allMatchingActive, clearSelection]);

    const [personFacets, setPersonFacets] = useState<PersonFacets | null>(null);
    const loadFacets = useCallback(() => {
        getPersonFacets().then(setPersonFacets).catch(() => setPersonFacets(null));
    }, []);
    useEffect(() => { loadFacets(); }, [loadFacets]);

    const refresh = useCallback(() => {
        clearSelection();
        reload();
        loadFacets();
    }, [clearSelection, reload, loadFacets]);

    const exportContacts = useCallback(
        (signal: AbortSignal, workspaceId: number) =>
            exportContactsCsv(
                { ...filterParams, q: query.trim() || undefined },
                { signal, headers: { 'X-Workspace-Id': String(workspaceId) } },
            ),
        [filterParams, query],
    );

    useEffect(() => subscribeToRecordMutations((entity) => {
        if (entity === 'contact') refresh();
    }), [refresh]);

    const facets = useMemo<ColumnFilterFacet[]>(() => {
        if (!personFacets) return [];
        const out: ColumnFilterFacet[] = [];
        const companyOptions = personFacets.companies.map((name) => ({ key: name, label: name }));
        if (personFacets.hasNoCompany) companyOptions.push({ key: FILTER_EMPTY, label: t('filterNoCompany') });
        if (companyOptions.length) out.push({ key: 'company', label: t('columnCompany'), options: companyOptions });
        const titleOptions = personFacets.titles.map((ti) => ({ key: ti, label: ti }));
        if (titleOptions.length) out.push({ key: 'title', label: t('columnTitle'), options: titleOptions });
        return out;
    }, [personFacets, t]);

    const [members, setMembers] = useState<WorkspaceMember[]>([]);
    useEffect(() => { getActiveWorkspaceMembers().then(setMembers).catch(() => setMembers([])); }, []);
    const memberById = useMemo(() => new Map(members.map((member) => [member.id, member])), [members]);
    const activeMembers = useMemo(() => members.filter((member) => member.status === 'active'), [members]);
    const ownerCounts = useMemo(
        () => new Map(personFacets?.owners?.map((facet) => [facet.key, facet.count]) ?? []),
        [personFacets],
    );
    const changeOwnerScope = useCallback((values: string[]) => {
        setFilterState({ ...filterState, owner: values });
    }, [filterState, setFilterState]);

    const facetFilterState = useMemo(() => withoutArchived(filterState), [filterState]);
    const hasActiveFilters = query.trim() !== '' || countActiveFilters(facetFilterState) > 0;
    const clearAll = useCallback(() => {
        setQuery('');
        setFilterState(showArchived ? { [ARCHIVED_FILTER_KEY]: [ARCHIVED_FILTER_VALUE] } : {});
    }, [setQuery, setFilterState, showArchived]);
    const effectiveOwnerValues = ownerScope.mode === 'me'
        ? [MEMBER_SCOPE_ME]
        : ownerScope.mode === 'unassigned'
            ? [FILTER_EMPTY]
            : ownerScope.memberIds.map(String);
    const ownerChips: FilterChipData[] = effectiveOwnerValues.map((value) => {
        const label = value === MEMBER_SCOPE_ME
            ? ts('me')
            : value === FILTER_EMPTY
                ? ts('unassigned')
                : memberById.get(Number(value))?.displayName ?? value;
        return {
            id: `owner:${value}`,
            label: `${ts('label')}: ${label}`,
            onRemove: () => changeOwnerScope(effectiveOwnerValues.filter((other) => other !== value)),
        };
    });
    const chips: FilterChipData[] = [
        ...(query.trim() ? [{ id: 'q', label: tf('chipSearch', { query: query.trim() }), onRemove: () => setQuery('') }] : []),
        ...ownerChips,
        ...facetChips(facets, filterState, setFilterState),
    ];

    const [isDeleting, setIsDeleting] = useState(false);
    const [editSheetOpen, setEditSheetOpen] = useState(false);
    const [drafts, setDrafts] = useState<Record<number, ContactDraft>>({});
    const [isSaving, setIsSaving] = useState(false);
    const [changeCompanyOpen, setChangeCompanyOpen] = useState(false);
    const [isClearingCompany, setIsClearingCompany] = useState(false);

    const [tags, setTags] = useState<Tag[]>([]);
    useEffect(() => { getTags().then(setTags).catch(() => setTags([])); }, []);

    const [bulkTag, setBulkTag] = useState<{ open: boolean; mode: 'add' | 'remove' }>({ open: false, mode: 'add' });
    const [bulkOwnerOpen, setBulkOwnerOpen] = useState(false);
    const selectedContactIds = useMemo(() => Array.from(selectedIds).map(Number), [selectedIds]);

    const selectAllMatching = useCallback(async () => {
        const requestId = selectAllRequestRef.current + 1;
        selectAllRequestRef.current = requestId;
        const requestSignature = filterSignature;
        setSelectingAll(true);
        try {
            const ids = await getContactIds({ ...filterParams, q: query || undefined });
            if (requestId !== selectAllRequestRef.current || requestSignature !== filterSignatureRef.current) return;
            setSelectedIds(new Set(ids));
            setMatchedSignature(requestSignature);
        } catch (err) {
            if (requestId === selectAllRequestRef.current) {
                toastError(err instanceof Error ? err.message : t('toastSelectAllFailed'));
            }
        } finally {
            if (requestId === selectAllRequestRef.current) setSelectingAll(false);
        }
    }, [filterParams, query, filterSignature, setSelectedIds, t]);

    const [tempByContactId, setTempByContactId] = useState<Map<number, RelationshipTemperature>>(new Map());
    useEffect(() => {
        if (showArchived) return;
        let cancelled = false;
        getContactTemperatures(contacts.map((contact) => contact.id))
            .then((temps) => {
                if (!cancelled) setTempByContactId(new Map(temps.map((temp) => [temp.id, temp])));
            })
            .catch(() => {
                if (!cancelled) setTempByContactId(new Map());
            });
        return () => {
            cancelled = true;
        };
    }, [contacts, showArchived]);

    const [newContactDialogOpen, setNewContactDialogOpen] = useState(false);
    const [isCreating, setIsCreating] = useState(false);
    const [creationSucceeded, setCreationSucceeded] = useState(false);
    const [newContactPayload, setNewContactPayload] = useState<CreateContactPayload>(EMPTY_CONTACT_DRAFT);
    const [imageFile, setImageFile] = useState<File | null>(null);
    const newContactCloseTimerRef = useRef<number | null>(null);
    const newContactGenerationRef = useRef(0);
    const invalidateNewContactClose = useCallback(() => {
        newContactGenerationRef.current += 1;
        if (newContactCloseTimerRef.current == null) return;
        window.clearTimeout(newContactCloseTimerRef.current);
        newContactCloseTimerRef.current = null;
    }, []);
    useEffect(() => () => invalidateNewContactClose(), [invalidateNewContactClose]);
    const openNewContactDialog = () => {
        invalidateNewContactClose();
        setNewContactDialogOpen(true);
    };
    const closeNewContactDialog = (open: boolean) => {
        invalidateNewContactClose();
        setNewContactDialogOpen(open);
        if (!open) {
            setNewContactPayload(EMPTY_CONTACT_DRAFT);
            setImageFile(null);
            setCreationSucceeded(false);
        }
    };

    const createNewContact = async (
        businessCard?: BusinessCardImportDraft,
        duplicateReviewToken: string | null = null,
    ) => {
        invalidateNewContactClose();
        const operationGeneration = newContactGenerationRef.current;
        const isCurrent = () => newContactGenerationRef.current === operationGeneration;
        setCreationSucceeded(false);
        setIsCreating(true);
        try {
            const imported = businessCard
                ? await importBusinessCard(businessCard)
                : null;
            const newContact = imported?.contact
                ?? await createContact({
                    ...newContactPayload,
                    duplicateReviewToken: duplicateReviewToken ?? undefined,
                });
            if (!isCurrent()) return;
            const reusedContact = imported?.disposition === 'reused';
            let avatarUploadFailed = false;
            if (imageFile && !reusedContact) {
                try {
                    await uploadContactPicture(newContact.id, imageFile);
                } catch {
                    avatarUploadFailed = true;
                }
                if (!isCurrent()) return;
            }
            if (isCurrent()) setIsCreating(false);
            let finalized = false;
            return {
                avatarUploadFailed,
                avatarUploaded: imageFile != null && !reusedContact && !avatarUploadFailed,
                finalize: () => {
                    if (finalized || !isCurrent()) return;
                    finalized = true;
                    toastSuccess(t(reusedContact ? 'toastCardAttached' : 'toastContactCreated'));
                    setCreationSucceeded(true);
                    invalidateNewContactClose();
                    const closeGeneration = newContactGenerationRef.current;
                    newContactCloseTimerRef.current = window.setTimeout(() => {
                        if (newContactGenerationRef.current !== closeGeneration) return;
                        newContactCloseTimerRef.current = null;
                        closeNewContactDialog(false);
                        refresh();
                    }, 900);
                },
            };
        } catch (err) {
            if (!isCurrent()) return;
            if (isFieldError(err) || businessCard) {
                throw err;
            }
            toastError(t('toastFailedCreate'));
        } finally {
            if (isCurrent()) setIsCreating(false);
        }
    };

    const openEditSheet = () => {
        const next: Record<number, ContactDraft> = {};
        for (const c of selectedContacts) next[c.id] = toDraft(c);
        setDrafts(next);
        setEditSheetOpen(true);
    };

    const updateDraft = (id: number, patch: Partial<ContactDraft>) => {
        setDrafts((prev) => ({ ...prev, [id]: { ...prev[id], ...patch } }));
    };

    const saveEdits = async () => {
        const changed = selectedContacts.filter((c) => {
            const draft = drafts[c.id];
            return draft && diffDraft(toDraft(c), draft);
        });

        if (changed.length === 0) {
            toast.info(t('toastNoChangesToSave'));
            setEditSheetOpen(false);
            return;
        }

        const invalid = changed.find((c) => !drafts[c.id].name.trim());
        if (invalid) {
            toast.error(t('toastNameRequiredForX', { name: invalid.name }));
            return;
        }

        setIsSaving(true);
        try {
            await Promise.all(
                changed.map((c) => {
                    const d = drafts[c.id];
                    const payload: UpdateContactPayload = {
                        name: d.name.trim(),
                        email: d.email.trim() || undefined,
                        phone: d.phone.trim() || undefined,
                        title: d.title.trim() || undefined,
                        companyId: c.companyId ?? c.company?.id ?? null,
                    };
                    return updateContact(c.id, payload);
                }),
            );
            toastSuccess(
                changed.length === 1 ? t('toastContactUpdated') : t('toastContactsUpdated', { count: changed.length }),
            );
            setEditSheetOpen(false);
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedSave'));
        } finally {
            setIsSaving(false);
        }
    };

    const bulkRemoveFromCompany = async () => {
        const affected = selectedContacts.filter((c) => c.companyId || c.company);
        if (affected.length === 0) {
            toast.info(t('toastNoneHaveCompany'));
            return;
        }
        setIsClearingCompany(true);
        try {
            await Promise.all(affected.map((c) => updateContact(c.id, {
                name: c.name,
                email: c.email || undefined,
                phone: c.phone || undefined,
                title: c.title || undefined,
                companyId: null,
            })));
            toastSuccess(
                affected.length === 1
                    ? t('toastRemovedFromCompany')
                    : t('toastRemovedNContactsFromCompanies', { count: affected.length }),
            );
            refresh();
        } catch (err) {
            toastError(err instanceof Error ? err.message : t('toastFailedRemoveFromCompany'));
        } finally {
            setIsClearingCompany(false);
        }
    };

    const quickEditOne = useCallback((contact: Contact) => {
        setMatchedSignature(null);
        setSelectedIds(new Set([contact.id]));
        setDrafts({ [contact.id]: toDraft(contact) });
        setEditSheetOpen(true);
    }, [setSelectedIds]);

    const archiveOne = useCallback((contact: Contact) => {
        setMatchedSignature(null);
        setSelectedIds(new Set([contact.id]));
        setDeleteDialogOpen(true);
    }, [setSelectedIds, setDeleteDialogOpen]);

    const confirmArchive = async () => {
        if (selectedContactIds.length === 0) return;
        setIsDeleting(true);
        try {
            const result = showArchived
                ? await bulkRestoreContacts(selectedContactIds)
                : await bulkArchiveContacts(selectedContactIds);
            const anySucceeded = notifyBulkResult(result, {
                success: (count) => count === 1
                    ? t(showArchived ? 'toastContactRestored' : 'toastContactArchived')
                    : t(showArchived ? 'toastContactsRestored' : 'toastContactsArchived', { count }),
                partial: (succeeded, total) => t(showArchived ? 'toastContactsRestoredPartial' : 'toastContactsArchivedPartial', { succeeded, total }),
                failure: (failed) => t(showArchived ? 'toastContactsRestoreFailed' : 'toastContactsArchiveFailed', { failed }),
            });
            setDeleteDialogOpen(false);
            if (anySucceeded) {
                setSelectedIds(new Set());
                refresh();
            }
        } catch (err) {
            toastError(err instanceof Error ? err.message : t(showArchived ? 'toastFailedRestore' : 'toastFailedArchive'));
        } finally {
            setIsDeleting(false);
        }
    };

    const applyBulkTag = useCallback((tagId: number) => {
        return bulkTag.mode === 'add'
            ? bulkAddTagToContacts(selectedContactIds, tagId)
            : bulkRemoveTagFromContacts(selectedContactIds, tagId);
    }, [bulkTag.mode, selectedContactIds]);

    const onBulkTagSuccess = useCallback(() => { setSelectedIds(new Set()); refresh(); }, [setSelectedIds, refresh]);

    const viewSelected = () => {
        const returnTo = `${window.location.pathname}${window.location.search}`;
        if (selectedContacts.length === 1) {
            router.push(recordDetailNavigationPath(
                'contacts',
                selectedContacts[0].id,
                returnSelection,
            ));
        } else {
            selectedContacts.forEach((contact) =>
                window.open(recordDetailPath('contacts', contact.id, returnTo), '_blank'));
        }
    };

    const inlineEdit = useInlineEdit<Contact>(patchItem);
    const saveContact = useCallback(
        (full: Contact) =>
            updateContact(full.id, {
                name: full.name,
                email: full.email,
                phone: full.phone,
                title: full.title,
                companyId: full.companyId ?? full.company?.id,
            }).then(() => {}),
        [],
    );

    const columns: ColumnDef<Contact>[] = useMemo(() => [
        { key: 'name', label: t('columnName'), getSortValue: (c) => c.name ?? null, widthClass: 'min-w-48' },
        {
            key: 'warmth',
            label: t('columnWarmth'),
            getSortValue: (c) => showArchived ? null : tempByContactId.get(c.id)?.score ?? null,
            sortable: false,
            render: (c) => <TemperaturePill temp={showArchived ? undefined : tempByContactId.get(c.id)} />,
        },
        {
            key: 'email',
            label: t('columnEmail'),
            getSortValue: (c) => c.email ?? null,
            editable: { getValue: (c) => c.email, save: (c, v) => inlineEdit.commit(c, 'email', v, saveContact) },
        },
        {
            key: 'phone',
            label: t('columnPhone'),
            getSortValue: (c) => c.phone ?? null,
            editable: { getValue: (c) => c.phone, save: (c, v) => inlineEdit.commit(c, 'phone', v, saveContact), inputType: 'tel' },
        },
        {
            key: 'company',
            label: t('columnCompany'),
            getSortValue: (c) => c.company?.name ?? null,
            render: (c) => c.company?.name,
            copyable: { label: t('copyableCompany'), getValue: (c) => c.company?.name ?? '' },
        },
        {
            key: 'title',
            label: t('columnTitle'),
            getSortValue: (c) => c.title ?? null,
            editable: { getValue: (c) => c.title, save: (c, v) => inlineEdit.commit(c, 'title', v, saveContact) },
        },
        {
            key: 'owner',
            label: t('columnOwner'),
            sortable: false,
            render: (c) => (c.ownerId != null ? memberById.get(c.ownerId)?.displayName ?? '' : ''),
        },
        {
            key: 'createdAt',
            label: t('columnCreated'),
            getSortValue: (c) => (c.createdAt ? Date.parse(c.createdAt) : null),
            render: (c) => c.createdAt,
        },
        {
            key: 'updatedAt',
            label: t('columnUpdated'),
            getSortValue: (c) => (c.updatedAt ? Date.parse(c.updatedAt) : null),
            render: (c) => c.updatedAt,
        },
    ], [t, tempByContactId, memberById, inlineEdit, saveContact, showArchived]);

    const { columns: customColumns, addColumnSlot } = useCustomFieldColumns('person', contacts);

    const selectionActions = showArchived ? (
        <Button variant="outline" size="sm" onClick={() => setDeleteDialogOpen(true)}>
            <ArchiveBoxIcon className="size-4" />
            {t('restore')}
        </Button>
    ) : (
        <ButtonGroup className="rounded-full bg-muted">
            {!allMatchingActive && (
                <>
                    <Button variant="outline" size="sm" onClick={viewSelected}>
                        <EyeIcon className="size-4" />
                        {t('view')}
                    </Button>
                    <Button variant="outline" size="sm" onClick={openEditSheet}>
                        <PencilIcon className="size-4" />
                        {t('quickEdit')}
                    </Button>
                </>
            )}
            <DropdownMenu>
                <DropdownMenuTrigger asChild>
                    <Button variant="outline" size="sm">
                        <EllipsisVerticalIcon className="size-4" />
                    </Button>
                </DropdownMenuTrigger>
                <DropdownMenuContent>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'add' }); }}>
                        <TagIcon />
                        {t('addTag')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkTag({ open: true, mode: 'remove' }); }}>
                        <TagIcon />
                        {t('removeTag')}
                    </DropdownMenuItem>
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setBulkOwnerOpen(true); }}>
                        <UserCircleIcon />
                        {t('assignOwner')}
                    </DropdownMenuItem>
                    {!allMatchingActive && (
                        <>
                            <DropdownMenuSeparator />
                            <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setChangeCompanyOpen(true); }}>
                                <BuildingOffice2Icon />
                                {t('changeCompany')}
                            </DropdownMenuItem>
                            <DropdownMenuItem
                                disabled={isClearingCompany}
                                onSelect={(e) => { e.preventDefault(); bulkRemoveFromCompany(); }}
                            >
                                <NoSymbolIcon />
                                {t('removeFromCompany')}
                            </DropdownMenuItem>
                        </>
                    )}
                    <DropdownMenuSeparator />
                    <DropdownMenuItem onSelect={(e) => { e.preventDefault(); setDeleteDialogOpen(true); }}>
                        <ArchiveBoxArrowDownIcon />
                        {t('archive')}
                    </DropdownMenuItem>
                </DropdownMenuContent>
            </DropdownMenu>
        </ButtonGroup>
    );

    const currentConfig: SavedViewConfig = useMemo(
        () => ({ filters: filterState, query, sortKey: sortKey === 'warmth' ? null : sortKey, sortDirection }),
        [filterState, query, sortKey, sortDirection],
    );
    const applyView = useCallback(
        (config: SavedViewConfig) => {
            setFilterState(config.filters ?? {});
            applyQuery(config.query ?? '');
            applySort(config.sortKey === 'warmth' ? null : config.sortKey ?? null, config.sortDirection ?? 'asc');
        },
        [setFilterState, applyQuery, applySort],
    );

    const { density, setDensity } = useRecordDensity();
    const mergedColumns = useMemo(() => [...columns, ...customColumns], [columns, customColumns]);
    const { visibleColumns, toggles, setColumnVisible, resetColumns, hiddenCount } = useColumnVisibility('person', mergedColumns, { lockedKey: sortKey });
    const peek = useRecordPeekController(
        'person',
        contacts,
        effectiveDisplayMode !== 'grid',
        returnSelection,
    );

    const recordRef = useCallback(
        (contact: Contact): ActiveRecordRef => ({ type: 'person', id: contact.id, label: contact.name }),
        [],
    );

    return (
        <div className="min-h-full bg-background px-2 pt-8 pb-12">
            <div className="mx-auto flex w-full max-w-[100rem] flex-col gap-10">
                <Rise>
                    <div className="flex items-center justify-between">
                        <h1 className="text-4xl font-extrabold">{t('heading')}</h1>
                        <div className="flex items-center gap-2">
                            {!showArchived && (
                                <RecordsActions
                                    entity="persons"
                                    onNew={openNewContactDialog}
                                    newLabel={t('new')}
                                    newAriaLabel={t('newAria')}
                                    onImported={refresh}
                                    onExport={exportContacts}
                                />
                            )}
                        </div>
                    </div>
                </Rise>

                <Rise delay={0.06}>
                    <SavedViewsBar
                        recordType="person"
                        initialViews={savedViews}
                        currentConfig={currentConfig}
                        onApply={applyView}
                        defaultView={defaultView}
                    />
                </Rise>

                <Rise delay={0.12}>
                    <FilterBar
                        reduce={reduce}
                        chips={chips}
                        hasActiveFilters={hasActiveFilters}
                        onClearAll={clearAll}
                        clearAllLabel={tf('clearAll')}
                        search={
                            <SearchField
                                value={query}
                                onChange={setQuery}
                                onClear={() => setQuery('')}
                                placeholder={t('searchPlaceholder')}
                                searchAria={tf('searchAria')}
                                clearAria={tf('clearSearchAria')}
                                className="min-w-0 flex-1 md:flex-initial"
                            />
                        }
                        collapsed={
                            <RecordsFilterSheet<Contact>
                                columns={columns}
                                sortKey={sortKey}
                                sortDirection={sortDirection}
                                onSortChange={onSortChange}
                                facets={facets}
                                filterState={filterState}
                                countedFilterState={facetFilterState}
                                onFilterStateChange={setFilterState}
                                ownerScope={{
                                    values: filterState.owner,
                                    onChange: changeOwnerScope,
                                    members: activeMembers,
                                    counts: ownerCounts,
                                }}
                                mobileControls={
                                    (showArchived || (personFacets?.archivedCount ?? 0) > 0) ? (
                                        <SegmentedToggle
                                            ariaLabel={t('archivedScopeAria')}
                                            value={showArchived ? 'archived' : 'active'}
                                            onChange={(next) => setShowArchived(next === 'archived')}
                                            options={[
                                                { value: 'active', label: t('scopeActive'), icon: <UsersIcon className="size-4" /> },
                                                { value: 'archived', label: t('scopeArchived', { count: personFacets?.archivedCount ?? 0 }), icon: <ArchiveBoxIcon className="size-4" /> },
                                            ]}
                                        />
                                    ) : undefined
                                }
                                hasAdditionalFilters={showArchived}
                                hasActiveFilters={hasActiveFilters}
                                onClearAll={clearAll}
                            />
                        }
                        trailing={
                            <div className="flex items-center gap-2">
                                {effectiveDisplayMode !== 'table' && (
                                    <RecordsSortMenu
                                        columns={columns}
                                        sortKey={sortKey}
                                        sortDirection={sortDirection}
                                        onSortChange={onSortChange}
                                    />
                                )}
                                <SegmentedToggle
                                    ariaLabel={t('displayModeAria')}
                                    value={displayMode}
                                    onChange={setDisplayMode}
                                    options={[
                                        { value: 'grid', icon: <Squares2X2Icon className="size-4" />, ariaLabel: t('gridViewAria') },
                                        { value: 'table', icon: <TableCellsIcon className="size-4" />, ariaLabel: t('tableViewAria') },
                                    ]}
                                    className="hidden md:inline-flex"
                                />
                                {effectiveDisplayMode === 'table' && <DensityToggle value={density} onChange={setDensity} />}
                                {effectiveDisplayMode === 'table' && (
                                    <ColumnVisibilityMenu
                                        toggles={toggles}
                                        onColumnVisibleChange={setColumnVisible}
                                        onReset={resetColumns}
                                        hiddenCount={hiddenCount}
                                    />
                                )}
                            </div>
                        }
                    >
                        {(showArchived || (personFacets?.archivedCount ?? 0) > 0) && (
                            <SegmentedToggle
                                ariaLabel={t('archivedScopeAria')}
                                value={showArchived ? 'archived' : 'active'}
                                onChange={(next) => setShowArchived(next === 'archived')}
                                options={[
                                    { value: 'active', label: t('scopeActive'), icon: <UsersIcon className="size-4" /> },
                                    { value: 'archived', label: t('scopeArchived', { count: personFacets?.archivedCount ?? 0 }), icon: <ArchiveBoxIcon className="size-4" /> },
                                ]}
                            />
                        )}
                        <MemberScopeFilter
                            values={filterState.owner}
                            onChange={changeOwnerScope}
                            members={activeMembers}
                            counts={ownerCounts}
                        />
                        <RecordsFilterPills<Contact>
                            facets={facets}
                            filterState={filterState}
                            onChange={setFilterState}
                        />
                    </FilterBar>
                </Rise>

                {(() => {
                    const pageFullySelected = contacts.length > 0 && contacts.every((contact) => selectedIds.has(contact.id));
                    const allMatchingSelected = allMatchingActive && total > contacts.length && selectedIds.size >= total;
                    const canSelectAllMatching = hasActiveFilters && pageFullySelected && total > contacts.length && selectedIds.size < total;
                    if (!canSelectAllMatching && !allMatchingSelected) return null;
                    return (
                        <div className="flex flex-wrap items-center justify-center gap-x-2 gap-y-1 rounded-lg bg-muted px-4 py-2 text-sm text-muted-foreground ring-1 ring-border">
                            {allMatchingSelected ? (
                                <>
                                    <span>{t('allMatchingSelected', { total })}</span>
                                    <button type="button" onClick={() => { setSelectedIds(new Set()); setMatchedSignature(null); }} className="font-medium text-brand transition hover:underline">
                                        {t('clearSelection')}
                                    </button>
                                </>
                            ) : (
                                <>
                                    <span>{t('pageSelected', { count: selectedContactIds.length })}</span>
                                    <button
                                        type="button"
                                        onClick={selectAllMatching}
                                        disabled={selectingAll || loading}
                                        className="font-medium text-brand transition hover:underline disabled:opacity-50"
                                    >
                                        {selectingAll ? t('selecting') : t('selectAllMatching', { total })}
                                    </button>
                                </>
                            )}
                        </div>
                    );
                })()}

                <Rise delay={0.18}>
                    <RecordsRenderView<Contact>
                        data={contacts}
                        columns={visibleColumns}
                        addColumnSlot={addColumnSlot}
                        renderCard={(item, { onQuickEdit, onDelete }) => (
                            <ContactCard
                                id={item.id}
                                name={item.name}
                                title={item.title}
                                company={item.company?.name}
                                email={item.email}
                                phone={item.phone}
                                imageUrl={item.imageUrl}
                                ownerName={item.ownerId != null ? memberById.get(item.ownerId)?.displayName : undefined}
                                onQuickEdit={onQuickEdit ? () => onQuickEdit(item) : undefined}
                                onDelete={onDelete ? () => onDelete(item) : undefined}
                                readOnly={showArchived}
                                removeIntent={showArchived ? 'restore' : 'archive'}
                                returnSelection={returnSelection}
                            />
                        )}
                        renderListRow={(item) => (
                            <ContactListRow
                                contact={item}
                                temperature={showArchived ? undefined : tempByContactId.get(item.id)}
                            />
                        )}
                        renderAvatar={(item) => <ContactAvatar contact={item} />}
                        detailPath={showArchived ? undefined : (item) => `/records/contacts/${item.id}`}
                        onRowClick={showArchived ? undefined : (item) => peek.openPeek(item.id)}
                        activeId={peek.activeId}
                        recordRef={recordRef}
                        displayMode={effectiveDisplayMode}
                        density={density}
                        selectedIds={selectedIds}
                        onSelectedIdsChange={handleSelectedIdsChange}
                        onQuickEdit={showArchived ? undefined : quickEditOne}
                        onDelete={archiveOne}
                        readOnly={showArchived}
                        removeIntent={showArchived ? 'restore' : 'archive'}
                        entityLabel="contact"
                        selectionActions={selectionActions}
                        loading={loading}
                        pagination={{ page, pageSize: size, total, onPageChange: setPage, onPageSizeChange: setSize }}
                        sortState={{ key: sortKey, direction: sortDirection, onSortChange }}
                    />
                </Rise>

                {peek.drawer}

                <QuickEditSheet
                    editSheetOpen={editSheetOpen}
                    setEditSheetOpen={setEditSheetOpen}
                    selectedIds={selectedIds}
                    selectedContacts={selectedContacts}
                    drafts={drafts}
                    updateDraft={updateDraft}
                    isSaving={isSaving}
                    saveEdits={saveEdits}
                />

                <NewContactDialog
                    newContactDialogOpen={newContactDialogOpen}
                    setNewContactDialogOpen={closeNewContactDialog}
                    newContactPayload={newContactPayload}
                    setNewContactPayload={setNewContactPayload}
                    imageFile={imageFile}
                    setImageFile={setImageFile}
                    isCreating={isCreating}
                    isSuccess={creationSucceeded}
                    createNewContact={createNewContact}
                    onRecoveredImport={refresh}
                />

                <ArchiveRecordDialog
                    open={deleteDialogOpen}
                    onOpenChange={setDeleteDialogOpen}
                    mode={showArchived ? 'restore' : 'archive'}
                    selectedIds={selectedIds}
                    selectedItems={selectedContacts}
                    entityLabel={t('entityLabel')}
                    entityLabelPlural={t('entityLabelPlural')}
                    getDisplayName={(c) => c.name}
                    isPending={isDeleting}
                    onConfirm={confirmArchive}
                />

                <ChangeCompanyDialog
                    open={changeCompanyOpen}
                    onOpenChange={setChangeCompanyOpen}
                    contacts={selectedContacts}
                />

                <BulkTagDialog
                    open={bulkTag.open}
                    onOpenChange={(open) => setBulkTag((s) => ({ ...s, open }))}
                    mode={bulkTag.mode}
                    count={selectedContactIds.length}
                    tags={tags}
                    messages={{
                        success: (count) => t(bulkTag.mode === 'add' ? 'toastTagAdded' : 'toastTagRemoved', { count }),
                        partial: (succeeded, total) => t(bulkTag.mode === 'add' ? 'toastTagAddedPartial' : 'toastTagRemovedPartial', { succeeded, total }),
                        failure: (failed) => t('toastTagFailed', { failed }),
                    }}
                    onApply={applyBulkTag}
                    onSuccess={onBulkTagSuccess}
                />

                <BulkAssignOwnerDialog
                    open={bulkOwnerOpen}
                    onOpenChange={setBulkOwnerOpen}
                    count={selectedContactIds.length}
                    members={activeMembers}
                    messages={{
                        success: (count) => t('toastOwnerAssigned', { count }),
                        partial: (succeeded, total) => t('toastOwnerAssignedPartial', { succeeded, total }),
                        failure: (failed) => t('toastOwnerFailed', { failed }),
                    }}
                    onApply={(ownerId) => bulkAssignPersonOwner(selectedContactIds, ownerId)}
                    onSuccess={onBulkTagSuccess}
                />
            </div>
        </div>
    );
}
