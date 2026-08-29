import React, { type ReactNode, type SVGProps } from 'react'

type IconName =
  | 'arrowLeft'
  | 'arrowRight'
  | 'book'
  | 'bookmark'
  | 'check'
  | 'chevronDown'
  | 'close'
  | 'columns'
  | 'download'
  | 'list'
  | 'logOut'
  | 'menu'
  | 'more'
  | 'moon'
  | 'pin'
  | 'pinOff'
  | 'plus'
  | 'refresh'
  | 'search'
  | 'settings'
  | 'sliders'
  | 'upload'
  | 'volume2'

const paths: Record<IconName, ReactNode> = {
  arrowLeft: <path d="m15 18-6-6 6-6" />,
  arrowRight: <path d="m9 18 6-6-6-6" />,
  book: <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H20v16H6.5A2.5 2.5 0 0 0 4 21.5v-16Z" /><path d="M4 19h16" /></>,
  bookmark: <path d="M6 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v17l-6-3.6L6 21V4Z" />,
  check: <path d="m5 12 4 4L19 6" />,
  chevronDown: <path d="m6 9 6 6 6-6" />,
  close: <path d="m6 6 12 12M18 6 6 18" />,
  columns: <><rect width="18" height="18" x="3" y="3" rx="2" /><path d="M12 3v18" /></>,
  download: <><path d="M12 3v12" /><path d="m7 10 5 5 5-5" /><path d="M5 21h14" /></>,
  list: <><path d="M9 6h11M9 12h11M9 18h11" /><path d="M4 6h.01M4 12h.01M4 18h.01" /></>,
  logOut: <><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" /><polyline points="16 17 21 12 16 7" /><line x1="21" y1="12" x2="9" y2="12" /></>,
  menu: <path d="M4 6h16M4 12h16M4 18h16" />,
  more: <path d="M5 12h.01M12 12h.01M19 12h.01" />,
  moon: <path d="M20.4 15.4A8.8 8.8 0 0 1 8.6 3.6 9 9 0 1 0 20.4 15.4Z" />,
  pin: <><line x1="12" y1="17" x2="12" y2="22" /><path d="M5 17h14l-2-6V4H7v7l-2 6Z" /></>,
  pinOff: <><line x1="2" y1="2" x2="22" y2="22" /><line x1="12" y1="17" x2="12" y2="22" /><path d="M5 17h14l-2-6V7M9 4h6v2" /></>,
  plus: <path d="M12 5v14M5 12h14" />,
  refresh: <><path d="M3 12a9 9 0 0 1 15-6.7L21 8" /><path d="M21 3v5h-5" /><path d="M21 12a9 9 0 0 1-15 6.7L3 16" /><path d="M3 21v-5h5" /></>,
  search: <><circle cx="10.8" cy="10.8" r="6.3" /><path d="m16 16 4.2 4.2" /></>,
  settings: <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.8 1.8 0 0 0 .36 1.98l.06.06-2.03 2.03-.06-.06a1.8 1.8 0 0 0-1.98-.36 1.8 1.8 0 0 0-1.1 1.65v.09h-2.88v-.09a1.8 1.8 0 0 0-1.1-1.65 1.8 1.8 0 0 0-1.98.36l-.06.06-2.03-2.03.06-.06A1.8 1.8 0 0 0 7 15a1.8 1.8 0 0 0-1.65-1.1h-.09v-2.88h.09A1.8 1.8 0 0 0 7 9.92a1.8 1.8 0 0 0-.36-1.98l-.06-.06 2.03-2.03.06.06a1.8 1.8 0 0 0 1.98.36 1.8 1.8 0 0 0 1.1-1.65v-.09h2.88v.09a1.8 1.8 0 0 0 1.1 1.65 1.8 1.8 0 0 0 1.98-.36l.06-.06 2.03 2.03-.06.06a1.8 1.8 0 0 0-.36 1.98 1.8 1.8 0 0 0 1.65 1.1h.09v2.88h-.09A1.8 1.8 0 0 0 19.4 15Z" /></>,
  sliders: <><path d="M4 6h16M4 12h16M4 18h16" /><circle cx="9" cy="6" r="2" /><circle cx="15" cy="12" r="2" /><circle cx="11" cy="18" r="2" /></>,
  upload: <><path d="M12 15V3" /><path d="m7 8 5-5 5 5" /><path d="M5 13v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6" /></>,
  volume2: <><path d="M11 5 6 9H3v6h3l5 4V5Z" /><path d="M15.5 9a4 4 0 0 1 0 6" /><path d="M18.5 6a8 8 0 0 1 0 12" /></>,
}

export function Icon({ name, ...props }: { name: IconName } & SVGProps<SVGSVGElement>) {
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...props}>{paths[name]}</svg>
}
