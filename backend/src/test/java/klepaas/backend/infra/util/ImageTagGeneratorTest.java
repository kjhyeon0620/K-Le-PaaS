package klepaas.backend.infra.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageTagGeneratorTest {

    @Test
    @DisplayName("40자리 커밋 SHA는 앞 7자리 short SHA로 변환한다")
    void toShortSha() {
        String commitHash = "abcdef1234567890abcdef1234567890abcdef12";

        assertThat(ImageTagGenerator.toShortSha(commitHash)).isEqualTo("abcdef1");
    }

    @Test
    @DisplayName("짧은 SHA도 그대로 태그로 사용한다")
    void toShortShaWithShortHash() {
        assertThat(ImageTagGenerator.toShortSha("abc1234")).isEqualTo("abc1234");
    }

    @Test
    @DisplayName("imageUri는 registry, imageName, short SHA를 조합한다")
    void buildImageUri() {
        String imageUri = ImageTagGenerator.buildImageUri(
                "registry.example.com",
                "owner-repo",
                "abcdef1234567890"
        );

        assertThat(imageUri).isEqualTo("registry.example.com/owner-repo:abcdef1");
    }

    @Test
    @DisplayName("빈 commitHash는 허용하지 않는다")
    void rejectBlankCommitHash() {
        assertThatThrownBy(() -> ImageTagGenerator.toShortSha(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commitHash");
    }
}
