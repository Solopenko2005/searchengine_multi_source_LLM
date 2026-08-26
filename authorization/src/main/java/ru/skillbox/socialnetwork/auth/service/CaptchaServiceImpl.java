package ru.skillbox.socialnetwork.auth.service;

import org.springframework.stereotype.Service;
import ru.skillbox.socialnetwork.auth.dto.response.CaptchaResponse;
import ru.skillbox.socialnetwork.auth.exception.FailedGenerateImageException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.UUID;

@Service
public class CaptchaServiceImpl implements CaptchaService {
    @Override
    public CaptchaResponse generateCaptcha() {
        String secret = UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        String image = generateImage(secret);
        return new CaptchaResponse(secret, image);
    }

    private String generateImage(String text) {
        int width = 160;
        int height = 60;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        int stripeHeight = height / 6;
        Color[] rainbowColors = {
                Color.RED, Color.ORANGE, Color.YELLOW,
                Color.GREEN, Color.BLUE, new Color(75, 0, 130)
        };

        for (int i = 0; i < rainbowColors.length; i++) {
            g2d.setColor(rainbowColors[i]);
            g2d.fillRect(0, i * stripeHeight, width, stripeHeight);
        }

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font font = new Font("Arial Black", Font.BOLD, 40);
        g2d.setFont(font);
        g2d.setColor(Color.BLACK);

        FontMetrics fm = g2d.getFontMetrics();
        int x = (width - fm.stringWidth(text)) / 2;
        int y = ((height - fm.getHeight()) / 2) + fm.getAscent();
        g2d.drawString(text, x, y);

        g2d.dispose();

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", baos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            throw new FailedGenerateImageException("Failed to generate image");
        }
    }
}
