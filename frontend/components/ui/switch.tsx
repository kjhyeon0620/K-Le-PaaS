'use client'

import * as React from 'react'
import * as SwitchPrimitive from '@radix-ui/react-switch'

import { cn } from '@/lib/utils'

function Switch({
  className,
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        'peer data-[state=checked]:bg-green-500 data-[state=unchecked]:bg-gray-300 focus-visible:border-ring focus-visible:ring-ring/50 dark:data-[state=unchecked]:bg-gray-600 dark:data-[state=checked]:bg-green-600 inline-flex h-5 w-9 shrink-0 items-center rounded-full border border-gray-400 shadow-sm transition-all outline-none focus-visible:ring-[3px] disabled:cursor-not-allowed disabled:opacity-50',
        className,
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className={
          'bg-white dark:data-[state=unchecked]:bg-gray-200 dark:data-[state=checked]:bg-white pointer-events-none block size-4 rounded-full ring-1 ring-gray-300 shadow-sm transition-transform data-[state=checked]:translate-x-[calc(100%-0.5px)] data-[state=unchecked]:translate-x-0.5'
        }
      />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
