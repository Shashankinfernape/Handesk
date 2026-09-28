import 'package:flutter/material.dart';
import '../theme/style.dart';

class TechInput extends StatelessWidget {
  final String? hintText;
  final TextEditingController? controller;
  final bool obscureText;
  final ValueChanged<String>? onChanged;

  const TechInput({
    Key? key,
    this.hintText,
    this.controller,
    this.obscureText = false,
    this.onChanged,
  }) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return TextField(
      controller: controller,
      obscureText: obscureText,
      onChanged: onChanged,
      style: AppTextStyles.body,
      decoration: InputDecoration(
        hintText: hintText,
        hintStyle: AppTextStyles.bodySecondary,
        filled: true,
        fillColor: AppColors.surface,
        contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 12),
        enabledBorder: const UnderlineInputBorder(
          borderSide: BorderSide(color: AppColors.border, width: 1),
        ),
        focusedBorder: const UnderlineInputBorder(
          borderSide: BorderSide(color: AppColors.accent, width: 2),
        ),
        errorBorder: const UnderlineInputBorder(
          borderSide: BorderSide(color: AppColors.error, width: 1),
        ),
      ),
      cursorColor: AppColors.accent,
    );
  }
}

class TechButton extends StatefulWidget {
  final String label;
  final VoidCallback? onPressed;

  const TechButton({
    Key? key,
    required this.label,
    this.onPressed,
  }) : super(key: key);

  @override
  _TechButtonState createState() => _TechButtonState();
}

class _TechButtonState extends State<TechButton> {
  bool _isHovered = false;

  @override
  Widget build(BuildContext context) {
    final bool isDisabled = widget.onPressed == null;
    
    return MouseRegion(
      onEnter: (_) => setState(() => _isHovered = true),
      onExit: (_) => setState(() => _isHovered = false),
      cursor: isDisabled ? SystemMouseCursors.basic : SystemMouseCursors.click,
      child: GestureDetector(
        onTap: widget.onPressed,
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
          decoration: BoxDecoration(
            color: _isHovered && !isDisabled ? AppColors.accent.withOpacity(0.1) : AppColors.surface,
            border: Border.all(
              color: _isHovered && !isDisabled ? AppColors.accent : AppColors.border,
              width: 1,
            ),
            borderRadius: BorderRadius.circular(4), // Compact, small rounding
          ),
          child: Text(
            widget.label,
            style: AppTextStyles.body.copyWith(
              color: isDisabled 
                  ? AppColors.textSecondary 
                  : (_isHovered ? AppColors.accent : AppColors.textPrimary),
              fontWeight: FontWeight.w500,
            ),
            textAlign: TextAlign.center,
          ),
        ),
      ),
    );
  }
}
