package dev.chatrelay;

import android.content.Context;
import android.graphics.*;
import android.view.View;

final class Icons extends View {
    private final String icon; private final int color;
    Icons(Context context, String icon, int color) { super(context); this.icon = icon; this.color = color; setContentDescription(icon); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); float size = Math.min(getWidth(),getHeight()) * .48f;
        canvas.save(); canvas.translate((getWidth()-size)/2,(getHeight()-size)/2); canvas.scale(size/24,size/24);
        Paint p = new Paint(3); p.setColor(color); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(1.5f); p.setStrokeCap(Paint.Cap.ROUND); p.setStrokeJoin(Paint.Join.ROUND);
        Path path = new Path();
        switch(icon) {
            case "Compose": canvas.drawLine(12,5,12,19,p); canvas.drawLine(5,12,19,12,p); break;
            case "More": p.setStyle(Paint.Style.FILL); canvas.drawCircle(12,5,1.5f,p); canvas.drawCircle(12,12,1.5f,p); canvas.drawCircle(12,19,1.5f,p); break;
            case "Search": canvas.drawCircle(10,10,6,p); canvas.drawLine(14.5f,14.5f,20,20,p); break;
            case "Back": path.moveTo(15,5); path.lineTo(8,12); path.lineTo(15,19); canvas.drawPath(path,p); break;
            case "Send": path.moveTo(12,20); path.lineTo(12,4); path.moveTo(6,10); path.lineTo(12,4); path.lineTo(18,10); canvas.drawPath(path,p); break;
            case "Video call": canvas.drawRoundRect(3,6,16,18,3,3,p); path.moveTo(16,10); path.lineTo(22,7); path.lineTo(22,17); path.lineTo(16,14); canvas.drawPath(path,p); break;
            case "Voice call": path.moveTo(7,3); path.lineTo(3,5); path.cubicTo(2,12,12,22,19,21); path.lineTo(21,17); path.lineTo(16,14); path.lineTo(13,17); path.cubicTo(10,15,8,13,7,10); path.lineTo(10,7); path.close(); canvas.drawPath(path,p); break;
            case "Attach": path.moveTo(9,17); path.lineTo(17,9); path.cubicTo(21,5,16,1,12,5); path.lineTo(4,13); path.cubicTo(-1,18,6,25,11,20); path.lineTo(20,11); path.moveTo(7,16); path.lineTo(15,8); canvas.drawPath(path,p); break;
            case "Play": p.setStyle(Paint.Style.FILL); path.moveTo(8,5); path.lineTo(20,12); path.lineTo(8,19); path.close(); canvas.drawPath(path,p); break;
            default: canvas.drawRoundRect(3,5,19,21,3,3,p); path.moveTo(9,15); path.lineTo(10,11); path.lineTo(18,3); path.lineTo(21,6); path.lineTo(13,14); path.close(); canvas.drawPath(path,p);
        }
        canvas.restore();
    }
}
