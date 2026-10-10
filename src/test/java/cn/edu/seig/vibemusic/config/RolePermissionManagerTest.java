package cn.edu.seig.vibemusic.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolePermissionManagerTest {

    @Test
    void allowsUsersToAccessFavoriteEndpoints() {
        RolePathPermissionsConfig config = new RolePathPermissionsConfig();
        config.setPermissions(Map.of(
                "ROLE_USER", List.of("/user/", "/favorite/"),
                "ROLE_ADMIN", List.of("/admin/")
        ));
        RolePermissionManager manager = new RolePermissionManager(config);

        assertTrue(manager.hasPermission("ROLE_USER", "/favorite/getFavoriteSongs"));
        assertTrue(manager.hasPermission("ROLE_USER", "/favorite/collectSong"));
        assertFalse(manager.hasPermission("ROLE_ADMIN", "/favorite/getFavoriteSongs"));
    }
}
