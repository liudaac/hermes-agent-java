import { CardHeader, CardTitle } from "@/components/ui/card";

/**
 * CardHeader pre-composed with an icon and title.
 * Replaces the repeated pattern:
 *   <CardHeader>
 *     <div className="flex items-center gap-2">
 *       <Icon className="h-5 w-5 text-muted-foreground" />
 *       <CardTitle className="text-base">{title}</CardTitle>
 *     </div>
 *   </CardHeader>
 */
export function CardHeaderIcon({
  icon: Icon,
  title,
  className,
}: {
  icon: React.ComponentType<{ className?: string }>;
  title: React.ReactNode;
  className?: string;
}) {
  return (
    <CardHeader className={className}>
      <div className="flex items-center gap-2">
        <Icon className="h-5 w-5 text-muted-foreground" />
        <CardTitle className="text-base">{title}</CardTitle>
      </div>
    </CardHeader>
  );
}
