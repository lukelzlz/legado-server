import React, { type SVGProps } from 'react'

export interface LogoProps extends SVGProps<SVGSVGElement> {
  size?: number | string
}

export function Logo({ className = '', size = 26, ...props }: LogoProps) {
  return (
    <svg
      viewBox="0 0 128 128"
      width={size}
      height={size}
      fill="none"
      className={`app-brand-logo ${className}`}
      aria-hidden="true"
      {...props}
    >
      <defs>
        <linearGradient id="logoTealGrad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#14B8A6" />
          <stop offset="50%" stopColor="#0D9488" />
          <stop offset="100%" stopColor="#0F766E" />
        </linearGradient>
        <linearGradient id="logoGoldGrad" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#FDE68A" />
          <stop offset="40%" stopColor="#F59E0B" />
          <stop offset="100%" stopColor="#D97706" />
        </linearGradient>
        <linearGradient id="logoGoldDark" x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#D97706" />
          <stop offset="100%" stopColor="#B45309" />
        </linearGradient>
      </defs>

      {/* Book Outer Wings */}
      <path
        d="M64 104 C48 97 28 98 18 102 C16.5 102.6 15 101.5 15 100 L15 36 C15 34.5 16.5 33.5 18 33 C28 29 48 30 64 37"
        stroke="url(#logoTealGrad)"
        strokeWidth="5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M64 104 C80 97 100 98 110 102 C111.5 102.6 113 101.5 113 100 L113 36 C113 34.5 111.5 33.5 110 33 C100 29 80 30 64 37"
        stroke="url(#logoTealGrad)"
        strokeWidth="5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />

      {/* Center Spine */}
      <line x1="64" y1="37" x2="64" y2="104" stroke="url(#logoTealGrad)" strokeWidth="4.5" strokeLinecap="round" />

      {/* Page Inset Accents */}
      <path d="M25 41 C36 38 48 39 58 44" stroke="url(#logoTealGrad)" strokeWidth="2.5" strokeLinecap="round" opacity="0.65" />
      <path d="M103 41 C92 38 80 39 70 44" stroke="url(#logoTealGrad)" strokeWidth="2.5" strokeLinecap="round" opacity="0.65" />

      {/* Top Bookmark Ribbon ('L' Stem) */}
      <path
        d="M48 36 L48 18 C48 13.5 53 10 57.5 12.5 C60.5 14 62 17 62 21 L62 30"
        stroke="url(#logoGoldDark)"
        strokeWidth="7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M43 38 L43 20 C43 14 48 9.5 53.5 11 C58 12.5 60 16 60 21 L60 36"
        stroke="url(#logoTealGrad)"
        strokeWidth="6.5"
        strokeLinecap="round"
      />
      <path d="M38 16 L48 13 L48 29 L43 25 L38 29 Z" fill="url(#logoGoldGrad)" />

      {/* Infinity Mobius Loop (∞) */}
      <path
        d="M48 64 C35 52 24 64 24 74 C24 84 37 90 49 80 C58 73 70 63 79 56 C88 49 104 53 104 69 C104 82 90 89 78 80 C68 72 58 64 48 56"
        stroke="url(#logoTealGrad)"
        strokeWidth="8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M46 66 C35 55 27 64 27 73 C27 80 38 85 48 77 C56 70 71 58 80 52 C89 46 101 51 101 65 C101 77 90 82 80 74 C71 67 56 55 46 66 Z"
        stroke="url(#logoGoldGrad)"
        strokeWidth="4.5"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  )
}
