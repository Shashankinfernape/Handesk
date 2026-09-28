import 'package:flutter/material.dart';

class AppColors {
  static const Color background = Color(0xFF151515);
  static const Color surface = Color(0xFF1D1D1D);
  static const Color elevated = Color(0xFF242424);
  static const Color border = Color(0xFF333333);
  static const Color accent = Color(0xFF00E5FF);
  static const Color success = Color(0xFF4CAF50);
  static const Color error = Color(0xFFF44336);
  
  static const Color textPrimary = Color(0xFFFFFFFF);
  static const Color textSecondary = Color(0xFFAAAAAA);
}

class AppTextStyles {
  static const TextStyle body = TextStyle(
    color: AppColors.textPrimary,
    fontSize: 14,
    fontWeight: FontWeight.normal,
    letterSpacing: 0,
  );

  static const TextStyle bodySecondary = TextStyle(
    color: AppColors.textSecondary,
    fontSize: 14,
    fontWeight: FontWeight.normal,
    letterSpacing: 0,
  );

  static const TextStyle heading = TextStyle(
    color: AppColors.textPrimary,
    fontSize: 18,
    fontWeight: FontWeight.w600,
    letterSpacing: 0.15,
  );

  static const TextStyle metadataLabel = TextStyle(
    color: AppColors.textSecondary,
    fontSize: 10,
    fontWeight: FontWeight.bold,
    letterSpacing: 1.2,
  );
}

class AppTheme {
  static ThemeData get darkTheme {
    return ThemeData(
      brightness: Brightness.dark,
      scaffoldBackgroundColor: AppColors.background,
      colorScheme: const ColorScheme.dark(
        primary: AppColors.accent,
        surface: AppColors.surface,
        error: AppColors.error,
        onPrimary: AppColors.background,
        onSurface: AppColors.textPrimary,
      ),
      textTheme: const TextTheme(
        bodyMedium: AppTextStyles.body,
        bodySmall: AppTextStyles.bodySecondary,
        titleLarge: AppTextStyles.heading,
        labelSmall: AppTextStyles.metadataLabel,
      ),
    );
  }
}
