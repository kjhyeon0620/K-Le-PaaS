/** @type {import('next').NextConfig} */
const nextConfig = {
  output: 'standalone',
  experimental: { optimizeCss: false },
  basePath: '/console',
  assetPrefix: '/console',
  images: {
    domains: ['lh3.googleusercontent.com', 'avatars.githubusercontent.com'],
    formats: ['image/avif', 'image/webp'],
  },
  env: {
    NEXT_PUBLIC_BASE_PATH: '/console',
  },
  async rewrites() {
    return [
      {
        source: '/api/:path*',
        destination: 'http://localhost:8080/api/:path*',
      },
    ]
  },
  async redirects() {
    return [
      // Avoid double basePath (/console/console)
      { source: '/', destination: '/console', permanent: false, basePath: false },
    ]
  },
}

export default nextConfig