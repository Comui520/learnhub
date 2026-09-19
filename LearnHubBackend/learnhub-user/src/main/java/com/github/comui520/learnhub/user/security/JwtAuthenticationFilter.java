package com.github.comui520.learnhub.user.security;

import com.github.comui520.learnhub.user.mapper.UserRoleMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.ServletException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtTokenTool jwtTool;

    private final UserRoleMapper userRoleMapper;

    public JwtAuthenticationFilter(
            JwtTokenTool jwtTool,
            UserRoleMapper userRoleMapper
    ) {
        this.jwtTool = jwtTool;
        this.userRoleMapper = userRoleMapper;
    }

    /**
     * Spring MVC SSE uses an async dispatch after the controller returns the stream.
     * Re-parse the bearer token on that dispatch so the stateless security context
     * is available while the stream is being written.
     */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(
            jakarta.servlet.http.HttpServletRequest request,
            jakarta.servlet.http.HttpServletResponse response,
            jakarta.servlet.FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            try {
                Long id = jwtTool.parseId(token);
                List<SimpleGrantedAuthority> authorities = userRoleMapper.selectPermissionCodesByUserId(id)
                        .stream()
                        .map(SimpleGrantedAuthority::new)
                        .toList();
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(id, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException e) {
                SecurityContextHolder.clearContext();
            }
        }
        filterChain.doFilter(request, response);

    }
}
