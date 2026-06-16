// import { motion } from 'motion/react';
// import { Attachment } from '@/app/lib/types';
// import { 
//     FileKind,
//     KIND_ICON,
//  } from '@/app/components/library/files/fileMeta';
// import { EASE_OUT } from '@/app/lib/constants';
// import { formatDate } from '@/app/lib/utils';
// import FileActionsMenu from '@/app/components/library/files/FileActionsMenu';
// import OwnerChip from '@/app/components/library/files/OwnerChip';
// import type { T } from '@/app/components/library/files/FilesBrowser';

// export default function FileCard({
//     attachment,
//     kind,
//     locale,
//     reduce,
//     t,
//     onDelete,
// }: {
//     attachment: Attachment;
//     kind: FileKind;
//     locale: string;
//     reduce: boolean;
//     t: T;
//     onDelete: () => void;
// }) {
//     const Icon = KIND_ICON[kind];
//     const isImage = kind === 'image';

//     return (
//         <motion.li
//             layout={!reduce}
//             initial={false}
//             exit={reduce ? { opacity: 0 } : { opacity: 0, scale: 0.96 }}
//             transition={{ duration: 0.18, ease: EASE_OUT }}
//         >
//             <motion.div
//                 whileHover={reduce ? undefined : { y: -3 }}
//                 transition={{ duration: 0.2, ease: EASE_OUT }}
//                 className="group flex flex-col overflow-hidden rounded-2xl ring-1 ring-border bg-card transition-shadow duration-200 hover:shadow-lg"
//             >
//                 <div className="flex items-center gap-2 px-3 py-2.5">
//                     <Icon className="size-4 shrink-0 text-muted-foreground" />
//                     <a
//                         href={attachment.url}
//                         target="_blank"
//                         rel="noopener noreferrer"
//                         title={attachment.fileName}
//                         className="min-w-0 flex-1 truncate text-sm font-medium text-foreground transition-colors hover:text-brand"
//                     >
//                         {attachment.fileName}
//                     </a>
//                     <FileActionsMenu attachment={attachment} t={t} onDelete={onDelete} />
//                 </div>

//                 <a
//                     href={attachment.url}
//                     target="_blank"
//                     rel="noopener noreferrer"
//                     title={attachment.fileName}
//                     className="relative block aspect-[4/3] border-t border-border bg-muted/50"
//                 >
//                     {isImage ? (
//                         <img
//                             src={attachment.url}
//                             alt=""
//                             loading="lazy"
//                             className="size-full object-cover transition-transform duration-300 group-hover:scale-[1.02]"
//                         />
//                     ) : (
//                         <span className="absolute inset-0 flex items-center justify-center text-muted-foreground/60">
//                             <Icon className="size-12" />
//                         </span>
//                     )}
//                 </a>

//                 <div className="flex items-center gap-2 px-3 py-2 text-xs text-muted-foreground">
//                     <OwnerChip attachment={attachment} t={t} className="min-w-0 flex-1" />
//                     <span className="shrink-0">{formatDate(attachment.createdAt, locale)}</span>
//                 </div>
//             </motion.div>
//         </motion.li>
//     );
// }