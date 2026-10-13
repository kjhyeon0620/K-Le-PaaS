"use client"

import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Badge } from "@/components/ui/badge"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Separator } from "@/components/ui/separator"
import { CheckCircle, XCircle, Clock, AlertCircle, Terminal, User, Calendar, Code, FileText } from "lucide-react"
import { formatTimeAgo } from "@/lib/utils"

interface CommandHistory {
  id: number
  command_text: string
  tool: string
  args: Record<string, any>
  result: Record<string, any> | null
  status: "pending" | "processing" | "completed" | "error" | "failed"
  error_message: string | null
  user_id: string | null
  created_at: string
  updated_at: string
}

interface CommandDetailDialogProps {
  command: CommandHistory
  open: boolean
  onOpenChange: (open: boolean) => void
}

export function CommandDetailDialog({ command, open, onOpenChange }: CommandDetailDialogProps) {
  const getStatusIcon = (status: string) => {
    switch (status) {
      case "completed":
        return <CheckCircle className="h-5 w-5 text-green-500" />
      case "processing":
      case "pending":
        return <Clock className="h-5 w-5 text-blue-500" />
      case "error":
      case "failed":
        return <XCircle className="h-5 w-5 text-red-500" />
      default:
        return <AlertCircle className="h-5 w-5 text-yellow-500" />
    }
  }

  const getStatusBadge = (status: string) => {
    switch (status) {
      case "completed":
        return <Badge className="bg-green-500">Success</Badge>
      case "processing":
      case "pending":
        return <Badge className="bg-blue-500">Processing</Badge>
      case "error":
      case "failed":
        return <Badge variant="destructive">Failed</Badge>
      default:
        return <Badge variant="outline">{status}</Badge>
    }
  }

  const formatJson = (obj: any) => {
    if (!obj) return "N/A"
    return JSON.stringify(obj, null, 2)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl max-h-[80vh]">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <Terminal className="h-5 w-5" />
            Command Details
          </DialogTitle>
          <DialogDescription>
            Detailed information about the command execution
          </DialogDescription>
        </DialogHeader>

        <ScrollArea className="max-h-[60vh] pr-4">
          <div className="space-y-6">
            {/* Status Section */}
            <div>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  {getStatusIcon(command.status)}
                  <div>
                    <h3 className="font-semibold">Execution Status</h3>
                    <p className="text-sm text-muted-foreground">Current command state</p>
                  </div>
                </div>
                {getStatusBadge(command.status)}
              </div>
            </div>

            <Separator />

            {/* Command Text Section */}
            <div>
              <div className="flex items-center gap-2 mb-3">
                <Terminal className="h-4 w-4 text-muted-foreground" />
                <h3 className="font-semibold">User Command</h3>
              </div>
              <div className="bg-muted p-4 rounded-lg">
                <p className="text-sm font-mono">{command.command_text}</p>
              </div>
            </div>

            {/* Tool & Metadata Section */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <Code className="h-4 w-4 text-muted-foreground" />
                  <h3 className="font-semibold text-sm">Tool</h3>
                </div>
                <p className="text-sm font-mono bg-muted px-3 py-2 rounded">{command.tool}</p>
              </div>
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <User className="h-4 w-4 text-muted-foreground" />
                  <h3 className="font-semibold text-sm">User</h3>
                </div>
                <p className="text-sm bg-muted px-3 py-2 rounded">{command.user_id || "Anonymous"}</p>
              </div>
            </div>

            {/* Timing Section */}
            <div className="grid grid-cols-2 gap-4">
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <Calendar className="h-4 w-4 text-muted-foreground" />
                  <h3 className="font-semibold text-sm">Created</h3>
                </div>
                <p className="text-sm bg-muted px-3 py-2 rounded">
                  {formatTimeAgo(command.created_at)}
                </p>
              </div>
              <div>
                <div className="flex items-center gap-2 mb-2">
                  <Calendar className="h-4 w-4 text-muted-foreground" />
                  <h3 className="font-semibold text-sm">Updated</h3>
                </div>
                <p className="text-sm bg-muted px-3 py-2 rounded">
                  {formatTimeAgo(command.updated_at)}
                </p>
              </div>
            </div>

            <Separator />

            {/* Arguments Section */}
            <div>
              <div className="flex items-center gap-2 mb-3">
                <FileText className="h-4 w-4 text-muted-foreground" />
                <h3 className="font-semibold">Arguments</h3>
              </div>
              <div className="bg-muted p-4 rounded-lg">
                <pre className="text-xs font-mono overflow-x-auto">
                  {formatJson(command.args)}
                </pre>
              </div>
            </div>

            {/* Result Section */}
            {command.result && (
              <div>
                <div className="flex items-center gap-2 mb-3">
                  <CheckCircle className="h-4 w-4 text-green-500" />
                  <h3 className="font-semibold">Result</h3>
                </div>
                <div className="bg-muted p-4 rounded-lg">
                  <pre className="text-xs font-mono overflow-x-auto">
                    {formatJson(command.result)}
                  </pre>
                </div>
              </div>
            )}

            {/* Error Section */}
            {command.error_message && (
              <div>
                <div className="flex items-center gap-2 mb-3">
                  <XCircle className="h-4 w-4 text-red-500" />
                  <h3 className="font-semibold text-red-600">Error Message</h3>
                </div>
                <div className="bg-red-50 dark:bg-red-950 border border-red-200 dark:border-red-800 p-4 rounded-lg">
                  <p className="text-sm text-red-600 dark:text-red-400 font-mono">
                    {command.error_message}
                  </p>
                </div>
              </div>
            )}
          </div>
        </ScrollArea>
      </DialogContent>
    </Dialog>
  )
}
