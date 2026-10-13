/**
 * Utility functions for formatting Docker image strings
 */

/**
 * Formats a Docker image string to show only <owner><repo>:<tag> format
 * with a 7-character tag
 * 
 * @param owner - Repository owner (e.g., "K-Le-PaaS")
 * @param repo - Repository name (e.g., "test01")
 * @param imageTag - Image tag (e.g., "3503e52")
 * @returns Formatted string like "K-Le-PaaS/test01:3503e52"
 */
export function formatImageDisplay(owner: string, repo: string, imageTag: string): string {
  if (!owner || !repo || !imageTag) {
    return `${owner || 'unknown'}/${repo || 'unknown'}:${imageTag || 'latest'}`
  }

  // Ensure tag is exactly 7 characters (truncate if longer, pad if shorter)
  const formattedTag = imageTag.length >= 7 
    ? imageTag.substring(0, 7)
    : imageTag.padEnd(7, '0')

  return `${owner}/${repo}:${formattedTag}`
}

/**
 * Formats a Docker image string from image name and tag (legacy function)
 * 
 * @param imageName - Full image name (e.g., "contest27-klepaas-build-handle.kr.ncr.ntruss.com/k-le-paas-test01")
 * @param imageTag - Image tag (e.g., "3503e52")
 * @returns Formatted string like "k-le-paas-test01:3503e52"
 */
export function formatImageDisplayFromName(imageName: string, imageTag: string): string {
  if (!imageName || !imageTag) {
    return `${imageName || 'unknown'}:${imageTag || 'latest'}`
  }

  // Extract repository name from the full image path
  // Handle cases like:
  // - "contest27-klepaas-build-handle.kr.ncr.ntruss.com/k-le-paas-test01"
  // - "registry.example.com/owner/repo"
  // - "owner/repo"
  const parts = imageName.split('/')
  const repoName = parts[parts.length - 1] // Get the last part after the last '/'

  // Ensure tag is exactly 7 characters (truncate if longer, pad if shorter)
  const formattedTag = imageTag.length >= 7 
    ? imageTag.substring(0, 7)
    : imageTag.padEnd(7, '0')

  return `${repoName}:${formattedTag}`
}

/**
 * Formats a Docker image string from a full image URL
 * 
 * @param fullImageUrl - Full image URL (e.g., "contest27-klepaas-build-handle.kr.ncr.ntruss.com/k-le-paas-test01:3503e52")
 * @returns Formatted string like "k-le-paas-test01:3503e52"
 */
export function formatImageFromUrl(fullImageUrl: string): string {
  if (!fullImageUrl) {
    return 'unknown:latest'
  }

  // Split by ':' to separate image name and tag
  const lastColonIndex = fullImageUrl.lastIndexOf(':')
  if (lastColonIndex === -1) {
    // No tag found, treat entire string as image name
    const parts = fullImageUrl.split('/')
    const repoName = parts[parts.length - 1]
    return `${repoName}:latest`
  }

  const imageName = fullImageUrl.substring(0, lastColonIndex)
  const imageTag = fullImageUrl.substring(lastColonIndex + 1)

  return formatImageDisplayFromName(imageName, imageTag)
}
