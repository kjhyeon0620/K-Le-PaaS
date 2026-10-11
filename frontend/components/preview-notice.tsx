import { Badge } from "@/components/ui/badge"

// 백엔드 API가 없는 화면·카드 표시 (#59). 실제 기능처럼 보이지 않게 한다 (AGENTS.md 코드 규칙)
export function PreviewBadge() {
  return <Badge variant="outline" className="text-[10px] font-normal text-muted-foreground">준비 중</Badge>
}

export function PreviewNotice({ children }: { children?: React.ReactNode }) {
  return (
    <div className="rounded-md border border-dashed bg-muted/40 px-4 py-3 text-sm text-muted-foreground">
      <span className="font-medium text-foreground">준비 중인 화면입니다.</span>{" "}
      {children ?? "백엔드와 연결되지 않아 값이 비어 있거나 예시입니다."}
    </div>
  )
}
