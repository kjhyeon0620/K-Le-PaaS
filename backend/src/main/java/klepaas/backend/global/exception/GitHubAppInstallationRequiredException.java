package klepaas.backend.global.exception;

import lombok.Getter;

@Getter
public class GitHubAppInstallationRequiredException extends BusinessException {

    private final String installUrl;

    public GitHubAppInstallationRequiredException(String owner, String repo, String installUrl) {
        super(ErrorCode.GITHUB_APP_INSTALLATION_REQUIRED,
                "GitHub App이 " + owner + "/" + repo + " 저장소에 설치되지 않았습니다.");
        this.installUrl = installUrl;
    }
}
