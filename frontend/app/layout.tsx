import type { Metadata } from 'next'
import { GeistSans } from 'geist/font/sans'
import { GeistMono } from 'geist/font/mono'
import { Analytics } from '@vercel/analytics/next'
import './globals.css'
import { Toaster } from '@/components/ui/toaster'
import SlackConnectedNotifier from '@/components/slack-connected-notifier'

export const metadata: Metadata = {
  title: 'K-Le-PaaS - AI-First Kubernetes Platform',
  description: 'AI-First Kubernetes PaaS Platform for seamless application deployment',
  generator: 'v0.app',
}

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode
}>) {
  return (
    <html lang="en">
      <body className={`font-sans ${GeistSans.variable} ${GeistMono.variable}`}>
        <SlackConnectedNotifier />
        {children}
        <Analytics />
        <Toaster />
      </body>
    </html>
  )
}
