/**
 * 클립보드에 텍스트를 복사하는 유틸리티 함수
 * @param text 복사할 텍스트
 * @returns Promise<boolean> 복사 성공 여부
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  try {
    // 최신 클립보드 API 사용
    await navigator.clipboard.writeText(text)
    return true
  } catch (error) {
    console.error('Failed to copy to clipboard:', error)
    
    // 폴백: 구형 브라우저나 HTTPS가 아닌 환경에서 사용
    try {
      const textArea = document.createElement('textarea')
      textArea.value = text
      textArea.style.position = 'fixed'
      textArea.style.left = '-999999px'
      textArea.style.top = '-999999px'
      document.body.appendChild(textArea)
      textArea.focus()
      textArea.select()
      
      const successful = document.execCommand('copy')
      document.body.removeChild(textArea)
      
      return successful
    } catch (fallbackError) {
      console.error('Fallback copy method also failed:', fallbackError)
      return false
    }
  }
}
