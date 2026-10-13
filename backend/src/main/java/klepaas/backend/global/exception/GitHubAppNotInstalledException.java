package klepaas.backend.global.exception;

public class GitHubAppNotInstalledException extends BusinessException {

    public GitHubAppNotInstalledException(String owner, String repo) {
        super(ErrorCode.GITHUB_APP_NOT_INSTALLED,
                "GitHub App이 " + owner + "/" + repo + " 저장소에 설치되지 않았습니다.");
    }
}
